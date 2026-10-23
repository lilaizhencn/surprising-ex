package com.surprising.aeron.service.core;

import com.surprising.aeron.protocol.CoreRiskLimitBracket;

import com.surprising.aeron.protocol.CoreInstrumentRiskLimits;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.RegisterInstrumentCommand;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.service.account.AccountLaneState;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.order.admission.ResolvedPlaceOrder;
import com.surprising.aeron.service.order.state.OrderRuntime;
import com.surprising.aeron.service.order.state.ReservationRuntime;
import com.surprising.aeron.service.position.PositionCloseCapacity;
import com.surprising.aeron.service.position.PositionRuntime;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
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
    private ReservationRuntime reservation;

    @Setup public void setup() {
        var instrument = CoreInstrument.from(ProductLine.LINEAR_PERPETUAL,
                new RegisterInstrumentCommand(
                        "1", "BTC", "USDT", "USDT", 1, 1, 1,
                        new CoreInstrumentRiskLimits(
                                100_000, (1_000_000_000_000L / (100_000)), Long.MAX_VALUE, 0, Long.MAX_VALUE,
                                java.util.List.of(new CoreRiskLimitBracket(Long.MAX_VALUE, (1_000_000_000_000L / (100_000)), 100_000, 50_000))),
                        0, 0, 0, -1, 0));
        reservation = new ReservationRuntime(11, 7, 0, ReservationKind.DERIVATIVE_MARGIN,
                0, 100, 0, 0, 1);
        var intent = new PlaceOrderCommand(11, "1", CoreOrderSide.BUY, 100, 1,
                false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                CoreTimeInForce.GTC, false, "client-11");
        resolved = new ResolvedPlaceOrder(intent, instrument, 0, 100, 100, 100,
                ReservationKind.DERIVATIVE_MARGIN, "USDT", 0, 0);
    }

    @Benchmark public OrderRuntime admitWithFinalMetadata(Blackhole blackhole) {
        blackhole.consume(reservation.release(0).consume(0).withRemainingUnits(100));
        return TradingAccountRuntime.preparedOrder(ProductLine.LINEAR_PERPETUAL, 7, resolved,
                commandId, 0, 1000, 2000);
    }

    /** Actual close-capacity decision moved from the Owner into Account Lane admission. */
    @Benchmark public java.util.List<Long> laneCloseCapacityDecision(CloseCapacityState state) {
        return PositionCloseCapacity.inspectLane(state.lane, 7, 0, 1,
                CorePositionSide.NET, CoreOrderSide.SELL, 0).conflictsFor(1);
    }

    @State(Scope.Thread)
    public static class CloseCapacityState {
        @Param({"0", "8", "64"}) public int liveOrders;
        AccountLaneState lane;

        @Setup public void setup() {
            var instrument = CoreInstrument.from(ProductLine.LINEAR_PERPETUAL,
                    new RegisterInstrumentCommand(
                            "1", "BTC", "USDT", "USDT", 1, 1, 1,
                            new CoreInstrumentRiskLimits(
                                    100_000, (1_000_000_000_000L / (100_000)), Long.MAX_VALUE, 0, Long.MAX_VALUE,
                                    java.util.List.of(new CoreRiskLimitBracket(Long.MAX_VALUE, (1_000_000_000_000L / (100_000)), 100_000, 50_000))),
                            0, 0, 0, -1, 0));
            lane = new AccountLaneState(0, 256);
            long quantity = Math.max(1, liveOrders);
            lane.storePosition(1, new PositionRuntime(7, 0, 0, CoreMarginMode.CROSS, CorePositionSide.NET,
                    instrument, quantity, 100, quantity * 100, 0, quantity * 10));
            for (int index = 0; index < liveOrders; index++) {
                lane.putOrder(new OrderRuntime(index + 1, 7, 0, instrument, CoreOrderSide.SELL, 110,
                        true, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                        CoreTimeInForce.GTC, 0, 0, 1, 0, 1, false).withCommitMetadata(1000, index + 1));
            }
        }
    }

    /** One Lane's real pending-order index, including last-order deletion and fresh group admission. */
    @Benchmark @OperationsPerInvocation(20)
    public long laneAdmissionOrderTurnover(AdmissionIndexState state) {
        for (var order : state.orders) state.lane.putOrder(order);
        long pending = state.lane.admissionOrderIndex(0).inspect(4, "1", CorePositionSide.NET,
                CoreOrderSide.BUY, CoreMarginMode.CROSS).pendingQuantity();
        for (var order : state.orders) state.lane.removeOrder(order.orderId());
        return pending;
    }

    @State(Scope.Thread)
    public static class AdmissionIndexState {
        @Param({"1", "20"}) public int users;
        @Param({"1", "4"}) public int symbols;
        AccountLaneState lane;
        final OrderRuntime[] orders = new OrderRuntime[20];

        @Setup public void setup() {
            lane = new AccountLaneState(0, 256); lane.bindOwner();
            for (int user = 1; user <= users; user++) lane.registerUser(4L * user);
            var instruments = new CoreInstrument[symbols];
            for (int symbol = 0; symbol < symbols; symbol++) instruments[symbol] = CoreInstrument.from(ProductLine.LINEAR_PERPETUAL,
                    new RegisterInstrumentCommand(
                            Integer.toString(symbol + 1), "BTC", "USDT", "USDT", 1, 1, 1,
                            new CoreInstrumentRiskLimits(
                                    100_000, (1_000_000_000_000L / (100_000)), Long.MAX_VALUE, 0, Long.MAX_VALUE,
                                    java.util.List.of(new CoreRiskLimitBracket(Long.MAX_VALUE, (1_000_000_000_000L / (100_000)), 100_000, 50_000))),
                            0, 0, 0, -1, 0));
            for (int item = 0; item < orders.length; item++) orders[item] = new OrderRuntime(1_000_000L + item,
                    4L * (item % users + 1), item % symbols, instruments[item % symbols], CoreOrderSide.BUY, 100,
                    false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                    CoreTimeInForce.GTC, 0, 0, 1, 0, 1, false);
        }
    }
}
