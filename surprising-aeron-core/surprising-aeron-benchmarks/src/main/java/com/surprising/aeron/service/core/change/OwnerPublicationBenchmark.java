package com.surprising.aeron.service.core.change;

import com.surprising.aeron.protocol.CoreRiskLimitBracket;

import com.surprising.aeron.protocol.CoreInstrumentRiskLimits;

import com.surprising.aeron.model.CoreOrderStatus;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.RegisterInstrumentCommand;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.service.account.AccountLaneState;
import com.surprising.aeron.service.account.UserRuntime;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.order.state.OrderRuntime;
import com.surprising.aeron.service.order.state.ReservationRuntime;
import com.surprising.aeron.service.settlement.MatcherSettlementChanges;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/** Measures one 20-entity publication/handoff, not trading business operations. */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
public class OwnerPublicationBenchmark {
    private final TradingAccountRuntime runtime = new TradingAccountRuntime();
    private final LaneCommitDelta delta = new LaneCommitDelta();
    private final RuntimeIndexedChangeBuffer<String, String> lane = new RuntimeIndexedChangeBuffer<>();
    private final OwnerIndexedChanges<String, String> owner = new OwnerIndexedChanges<>();
    private final UserRuntime[] users = new UserRuntime[20];
    private final com.surprising.aeron.support.collection.PrimitiveLongChangeSet changedIds =
            new com.surprising.aeron.support.collection.PrimitiveLongChangeSet(20);
    private long coreSequence;
    private MatcherSettlementChanges settlement;
    private AccountLaneState settlementLane;
    private final RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
    private final OrderRuntime[] settlementOrders = new OrderRuntime[20];
    private final ReservationRuntime[] settlementReservations = new ReservationRuntime[20];

    @Setup public void setup() {
        for (int i = 0; i < users.length; i++) {
            users[i] = new UserRuntime(i + 1);
            runtime.accountLane(runtime.topology().accountLaneId(i + 1L)).storeUser(i + 1, users[i]);
        }
        int laneId = runtime.topology().accountLaneId(7);
        settlementLane = runtime.accountLane(laneId);
        settlement = new MatcherSettlementChanges(runtime.topology().accountLaneCount());
        settlement.ensureOrderCapacity(settlementOrders.length, 1L << laneId);
        var instrument = CoreInstrument.from(ProductLine.LINEAR_PERPETUAL,
                new RegisterInstrumentCommand(
                        "1", "BTC", "USDT", "USDT", 1, 1, 1,
                        new CoreInstrumentRiskLimits(
                                100_000, (1_000_000_000_000L / (100_000)), Long.MAX_VALUE, 0, Long.MAX_VALUE,
                                java.util.List.of(new CoreRiskLimitBracket(Long.MAX_VALUE, (1_000_000_000_000L / (100_000)), 100_000, 50_000))),
                        0, 0, 0, -1, 0));
        for (int i = 0; i < settlementOrders.length; i++) {
            boolean terminal = i % 4 == 0;
            settlementOrders[i] = new OrderRuntime(i + 1, ProductLine.LINEAR_PERPETUAL,
                    7, 0, instrument, CoreOrderSide.BUY, 100, 1, terminal ? 1 : 0,
                    terminal ? 0 : 1, false, CoreMarginMode.CROSS, CorePositionSide.NET,
                    CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "client-" + i,
                    new UUID(1, i + 1), 0, 0, 0, 1000, 1000, 2000,
                    terminal ? CoreOrderStatus.FILLED : CoreOrderStatus.OPEN, 1);
            settlementReservations[i] = new ReservationRuntime(i + 1, 7, 0,
                    ReservationKind.DERIVATIVE_MARGIN, 0, 100, 0, terminal ? 100 : 0, 1);
        }
    }

    /** One actual preparation of 20 already-applied order/reservation changes; no trading or transport. */
    @Benchmark public int prepareOrderSettlement() {
        int laneId = settlementLane.laneId();
        var changes = settlement.laneDelta(laneId);
        for (int i = 0; i < settlementOrders.length; i++) {
            changes.putOrder(i + 1, settlementOrders[i]);
            changes.putReservation(i + 1, settlementReservations[i]);
        }
        settlement.prepareLaneTerminal(laneId, identities, settlementLane, 1);
        int terminals = changes.terminalOrderCount();
        changes.clear();
        return terminals;
    }

    /** Twenty actual account lookups with Owner scope entry/exit; no matching or transport. */
    @Benchmark public long readAccountsInOwnerScopes() {
        long result = 0;
        for (var user : users) {
            long userId = user.userId();
            result += runtime.onLane(userId, lane -> runtime.requireUser(userId)).userId();
        }
        return result;
    }

    /** Empty recycled buffers must not rescan their retained hash-table capacity. */
    @Benchmark public void clearRecycledBuffers() {
        delta.clear();
        runtime.treasury().clearChangedKeys();
    }

    @Benchmark public Object handoffAndQueryPreparedChanges() {
        for (int i = 0; i < 20; i++) lane.putIndexed(i, "position", true, i % 2 == 0 ? null : "index");
        owner.adopt(0, lane);
        Object result = owner.get(19);
        owner.clear();
        return result;
    }

    /** One 20-ID change set with duplicate writes; this is not Core business throughput. */
    @Benchmark public long collectChangedIds() {
        changedIds.clear();
        long first = (++coreSequence) * 20;
        for (int i = 0; i < 20; i++) changedIds.add(first + i);
        for (int i = 0; i < 20; i++) changedIds.add(first + i);
        return changedIds.valueAt(19);
    }

    @TearDown public void close() { runtime.close(); }
}
