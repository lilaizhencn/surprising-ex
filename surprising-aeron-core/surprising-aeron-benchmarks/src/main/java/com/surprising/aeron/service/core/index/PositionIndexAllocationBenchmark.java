package com.surprising.aeron.service.core.index;

import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.change.RuntimePositionChangeBuffer;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.position.PositionRuntime;
import com.surprising.aeron.service.snapshot.state.RuntimeStateProjector;
import com.surprising.product.api.ProductLine;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/** Publication buffer and actual OI/ADL/user-index application, excluding account settlement/Aeron. */
@State(Scope.Thread) @BenchmarkMode(Mode.Throughput) @OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1) @Measurement(iterations = 3, time = 1)
@Fork(value = 1, jvmArgsAppend = {"-Xms512m", "-Xmx512m", "-XX:+UseG1GC"})
public class PositionIndexAllocationBenchmark {
    @Param({"RESIZE", "REVERSE", "CLOSE_REOPEN"}) public String transition;
    private TradingAccountRuntime runtime;
    private RuntimeIdentityRegistry identities;
    private PositionRuntime source;
    private RuntimeFactIndexes indexes;
    private OpenInterestIndex openInterest;
    private AdlPositionIndex adl;
    private long generation, previousQuantity;
    private final RuntimePositionChangeBuffer positions = new RuntimePositionChangeBuffer();

    @Setup public void setup() {
        var state = TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL);
        identities = new RuntimeIdentityRegistry();
        runtime = RuntimeStateProjector.project(state, identities);
        var instrument = CoreInstrument.from(ProductLine.LINEAR_PERPETUAL,
                new RegisterInstrumentCommand(
                        "1", "BTC", "USDT", "USDT", 1, 1, 1,
                        new CoreInstrumentRiskLimits(
                                100_000, (1_000_000_000_000L / (100_000)), Long.MAX_VALUE, 0, Long.MAX_VALUE,
                                java.util.List.of(new CoreRiskLimitBracket(Long.MAX_VALUE, (1_000_000_000_000L / (100_000)), 100_000, 50_000))),
                        0, 0, 0, -1, 0));
        int symbol = identities.symbolId("1"), asset = identities.assetId("USDT");
        source = new PositionRuntime(7, symbol, asset, CoreMarginMode.CROSS, CorePositionSide.NET,
                instrument, 2, 100, 200, 0, 20);
        openInterest = new OpenInterestIndex(state);
        adl = new AdlPositionIndex(state, identities);
        indexes = new RuntimeFactIndexes(new PositionUserIndex(state), openInterest,
                new TriggerOrderIndex(state), new AlgoOrderIndex(state), new LiquidationIndex(state),
                new CancelAllAfterIndex(state), new ActiveOrderIndex(state), adl);
    }

    @Benchmark @OperationsPerInvocation(256)
    public long publishAndUpdateIndexes() {
        long checksum = 0;
        for (int i = 0; i < 256; i++) {
            boolean alternate = (++generation & 1) != 0;
            long quantity = switch (transition) {
                case "RESIZE" -> alternate ? 2 : 3;
                case "REVERSE" -> alternate ? 2 : -2;
                default -> alternate ? 2 : 0;
            };
            source.applyInPlace(source.instrument(), quantity, quantity == 0 ? 0 : 100,
                    Math.abs(quantity) * 100, 0, Math.abs(quantity) * 10);
            var old = adl.value(1);
            if (old != null && old.signedQuantitySteps() != previousQuantity)
                throw new IllegalStateException("index retained a borrowed position cell");
            positions.put(1, source);
            indexes.applyPositions(positions, identities, null);
            long actual = openInterest.openInterestSteps("1");
            if (actual != Math.abs(quantity) || adl.value(1).signedQuantitySteps() != quantity)
                throw new IllegalStateException("position indexes differ from the after-image");
            positions.clear();
            previousQuantity = quantity;
            checksum += actual;
        }
        return checksum;
    }

    @TearDown public void close() { runtime.close(); }
}
