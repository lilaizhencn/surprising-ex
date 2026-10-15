package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.state.instrument.CoreInstrument;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import exchange.core2.core.common.*;
import exchange.core2.core.common.cmd.*;
import org.openjdk.jmh.annotations.*;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Reused no-trade cancellation fact across real Account/Ledger threads; technical stage, not API capacity. */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = {"-Xms512m", "-Xmx1g", "-XX:+UseG1GC"})
@State(Scope.Thread)
public class MatcherTreasuryReceiptBenchmark {
    @Param({"1", "4"}) public int laneCount;
    private TradingRuntimeState runtime;
    private RuntimeIdentityRegistry identities;
    private SystemLedgerLane ledger;
    private OrderRuntime original;
    private MatcherResult nativeResult;
    private long sequence;
    private final UUID commandId = new UUID(1, 2);

    @Setup public void setup() {
        runtime = new TradingRuntimeState(new LaneTopology(LaneTopology.ROUTE_VERSION, 1, 0, 0,
                laneCount, LaneTopology.DEFAULT_ACCOUNT_LANE_SEED, 256, 256, 256));
        identities = new RuntimeIdentityRegistry();
        runtime.setMetadata(ProductLine.LINEAR_PERPETUAL, 0);
        var instrument = CoreInstrument.from(ProductLine.LINEAR_PERPETUAL,
                new RegisterInstrumentCommand("1", ContractType.LINEAR_PERPETUAL.ordinal(),
                        "BTC", "USDT", "USDT", 1, 1, 1, 100_000, 50_000, 0, 0, 0, -1, 0));
        runtime.registerInstrument(instrument);
        int symbol = identities.symbolId("1");
        original = new OrderRuntime(11, 1, symbol, instrument, CoreOrderSide.BUY, 100,
                false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                CoreTimeInForce.GTC, 0, 0, 1, 0, 1, false);
        runtime.putOrder(original);
        runtime.clearChangedKeys();
        nativeResult = new MatcherResult(1, OrderCommandType.CANCEL_ORDER, 11, symbol,
                100, 1, 100, OrderAction.BID, OrderType.GTC, 1, 1_000, 0,
                CommandResultCode.MATCHING_UNKNOWN_ORDER_ID, List.of(),
                new MatcherResult.MarketData(List.of(), List.of(), 0, 0));
        runtime.startAccountLanes();
        ledger = new SystemLedgerLane(runtime.treasury(), 256, ignored -> { });
        runtime.enterAsynchronousCommandScope();
        runtime.releaseOwnerLaneAccess();
    }

    @Benchmark public long completeFinancialReceipt() {
        long currentSequence = ++sequence;
        var event = runtime.prepareDirectCancellation(currentSequence, original.userId(), original.orderId(), commandId, 0, identities, 10, 20);
        if (!event.submitTreasurySettlement(ledger)) throw new IllegalStateException("financial slot not admitted");
        event.reserveMatcherPublication();
        event.beginMatcherPublication();
        // Matcher payload is a retained no-trade fixture; the Core proof/commit sequence
        // still increases for every Account application. This is not an API load generator.
        event.publishDirectNativeResult(nativeResult, currentSequence, 1, 2, 11, currentSequence, 0);
        event.completeMatcherPublication();
        while (!event.complete()) { ledger.assertHealthy(); runtime.assertAccountLanesHealthy(); Thread.onSpinWait(); }
        runtime.collectMatcherSettlement(event, null, null);
        long sequence = event.directResultCoreSequence();
        runtime.releaseMatcherSettlement(event);
        return sequence;
    }

    @TearDown public void close() {
        runtime.exitAsynchronousCommandScope();
        ledger.close();
        if (runtime.treasury().assetLedgerEntryCount() != 0)
            throw new IllegalStateException("no-trade fact changed Treasury money");
        runtime.close();
    }
}
