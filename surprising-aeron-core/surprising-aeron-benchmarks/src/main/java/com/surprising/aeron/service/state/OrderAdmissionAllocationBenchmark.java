package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.protocol.UpsertInstrumentCommand;
import com.surprising.aeron.service.state.instrument.CoreInstrumentState;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/** Actual immutable admission constructor; input decoding and trading are outside this microbenchmark. */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
public class OrderAdmissionAllocationBenchmark {
    private ResolvedPlaceOrder resolved;
    private final UUID commandId = new UUID(1, 1);
    private final ReservationRuntime reservation = new ReservationRuntime(11, 7, 0, 100);

    @Setup public void setup() {
        var instrument = CoreInstrumentState.from(ProductLine.LINEAR_PERPETUAL,
                new UpsertInstrumentCommand("BTC-USDT", 1, ContractType.LINEAR_PERPETUAL.ordinal(),
                        "BTC", "USDT", "USDT", 1, 1, 1, 100_000, 50_000, 0, 0, 0, -1, 0));
        var intent = new PlaceOrderCommand(11, "BTC-USDT", 1, CoreOrderSide.BUY, 100, 1,
                false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                CoreTimeInForce.GTC, false, "client-11");
        resolved = new ResolvedPlaceOrder(intent, instrument, 0, 100, 100, 100,
                1, ReservationKind.DERIVATIVE_MARGIN, "USDT", 0, 0, 1);
    }

    @Benchmark public OrderRuntime admitWithFinalMetadata(Blackhole blackhole) {
        blackhole.consume(reservation.release(0).consume(0).withRemainingUnits(100));
        return TradingRuntimeState.preparedOrder(ProductLine.LINEAR_PERPETUAL, 7, resolved,
                commandId, 0, 1000, 2000);
    }
}
