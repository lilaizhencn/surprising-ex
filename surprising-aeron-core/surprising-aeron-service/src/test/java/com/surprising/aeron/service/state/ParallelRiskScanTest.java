package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.state.model.*;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import static org.assertj.core.api.Assertions.*;

class ParallelRiskScanTest {
    private static final LaneTopology TOPOLOGY = LaneTopology.productionDefault();
    private static final String SYMBOL = "BTC-USDT";

    @Test
    void slowLaneDoesNotPreventOtherLanesFromValuingAndIdsIgnoreCompletionOrder() throws Exception {
        var source = source(CoreMarginMode.ISOLATED, 1);
        var slowFirst = scanWithBlockedLane(source, 0);
        var slowLast = scanWithBlockedLane(source, TOPOLOGY.accountLaneCount() - 1);
        assertThat(slowFirst).isEqualTo(slowLast);
        assertThat(slowFirst.riskState().nextLiquidationId()).isEqualTo(TOPOLOGY.accountLaneCount() + 1L);
        for (int lane = 0; lane < TOPOLOGY.accountLaneCount(); lane++)
            assertThat(TOPOLOGY.accountLaneId(slowFirst.riskState().liquidations().get(lane + 1L).userId()))
                    .isEqualTo(lane);
        assertThat(slowFirst.users()).isEqualTo(source.users());
        assertThat(slowFirst.treasuryState()).isEqualTo(source.treasuryState());
    }

    private TradingCoreState scanWithBlockedLane(TradingCoreState source, int blockedLane) throws Exception {
        var ids = new RuntimeIdentityRegistry();
        try (var runtime = RuntimeStateProjector.project(source, ids, TOPOLOGY)) {
            var index = new PositionUserIndex(source, ids, TOPOLOGY);
            RuntimeDerivativeRiskProcessor.applyMarkPriceRuntime(mark(1, 80), runtime, ids);
            runtime.startAccountLanes();
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            runtime.laneWorkers[blockedLane].submit(lane -> {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("test release timeout"); }
                catch (InterruptedException e) { throw new AssertionError(e); }
            });
            try {
                assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
                runtime.enterAsynchronousCommandScope();
                var work = new RiskScanCoordinator(TOPOLOGY.accountLaneCount(), index, runtime, ids);
                assertThat(work.poll()).isFalse();
                for (int lane = 0; lane < TOPOLOGY.accountLaneCount(); lane++) {
                    if (lane == blockedLane) continue;
                    final int id = lane;
                    await(() -> runtime.laneMutationTasks[id].completed);
                    var page = (RiskLaneProcessor.Page) runtime.laneMutationTasks[lane].result;
                    assertThat(page.creations().size()).isEqualTo(1);
                }
                assertThat(runtime.ownerLaneAccess).isFalse();
                assertThat(runtime.nextLiquidationId()).isEqualTo(1);
                assertThat(work.poll()).isFalse();
                assertThatThrownBy(runtime::requireSnapshotFenceReady).hasMessageContaining("unfinished");
                release.countDown();
                await(work::poll);
                assertThat(work.completedWork()).isEqualTo(TOPOLOGY.accountLaneCount());
                assertThat(work.poll()).isTrue();
                assertThat(runtime.ownerLaneAccess).isFalse();
            } finally {
                release.countDown();
                runtime.exitAsynchronousCommandScope();
            }
            return RuntimeStateMaterializer.materialize(runtime, ids);
        }
    }

    @Test
    void partialProgressOnEveryLaneSurvivesSnapshotSmallBudgetsAndNewPrice() {
        var source = source(CoreMarginMode.CROSS, 2);
        var ids = new RuntimeIdentityRegistry();
        try (var runtime = RuntimeStateProjector.project(source, ids, TOPOLOGY)) {
            var index = new PositionUserIndex(source, ids, TOPOLOGY);
            RuntimeDerivativeRiskProcessor.applyMarkPriceRuntime(mark(1, 80), runtime, ids);
            runtime.startAccountLanes();
            run(runtime, ids, index, TOPOLOGY.accountLaneCount());
            var partial = RuntimeStateMaterializer.materialize(runtime, ids);
            assertThat(partial.riskState().scan().laneProgress()).hasSize(TOPOLOGY.accountLaneCount())
                    .allMatch(cursor -> cursor.userId() > 0 && !cursor.complete());
            var restoredState = TradingStateSnapshotCodec.decode(TradingStateSnapshotCodec.encode(partial),
                    ProductLine.LINEAR_PERPETUAL);
            assertThat(restoredState).isEqualTo(partial);
            var restoredIds = new RuntimeIdentityRegistry();
            try (var restored = RuntimeStateProjector.project(restoredState, restoredIds, TOPOLOGY)) {
                restored.startAccountLanes();
                var restoredIndex = new PositionUserIndex(restoredState, restoredIds, TOPOLOGY);
                RuntimeDerivativeRiskProcessor.applyMarkPriceRuntime(mark(2, 70), runtime, ids);
                RuntimeDerivativeRiskProcessor.applyMarkPriceRuntime(mark(2, 70), restored, restoredIds);
                int[] budgets = {1, 3, 7};
                for (int page = 0; page < 128 && runtime.firstRiskIncompleteScan() != null; page++) {
                    int budget = budgets[page % budgets.length];
                    assertThat(run(runtime, ids, index, budget)).isBetween(1, budget);
                    run(restored, restoredIds, restoredIndex, budget);
                    var expected = RuntimeStateMaterializer.materialize(runtime, ids);
                    var actual = RuntimeStateMaterializer.materialize(restored, restoredIds);
                    assertThat(actual).isEqualTo(expected);
                    assertThat(actual.businessStateHash()).isEqualTo(expected.businessStateHash());
                    assertThat(actual.users()).isEqualTo(source.users());
                    assertThat(actual.treasuryState()).isEqualTo(source.treasuryState());
                }
                assertThat(runtime.firstRiskIncompleteScan()).isNull();
                var complete = RuntimeStateMaterializer.materialize(runtime, ids);
                assertThat(complete.riskState().snapshots()).hasSize(2 * TOPOLOGY.accountLaneCount());
                assertThat(complete.riskState().snapshots().values()).allMatch(risk -> risk.priceSequence() == 2);
                assertThat(complete.riskState().liquidations()).hasSize(2 * TOPOLOGY.accountLaneCount());
                assertThat(complete.riskState().nextLiquidationId()).isEqualTo(2 * TOPOLOGY.accountLaneCount() + 1L);
            }
        }
    }

    @Test
    void idOverflowCannotPartiallyPublishNewLiquidations() {
        var source = source(CoreMarginMode.ISOLATED, 1);
        var ids = new RuntimeIdentityRegistry();
        try (var runtime = RuntimeStateProjector.project(source, ids, TOPOLOGY)) {
            var index = new PositionUserIndex(source, ids, TOPOLOGY);
            RuntimeDerivativeRiskProcessor.applyMarkPriceRuntime(mark(1, 80), runtime, ids);
            runtime.setNextLiquidationId(Long.MAX_VALUE - 1);
            runtime.startAccountLanes();
            assertThatThrownBy(() -> run(runtime, ids, index, TOPOLOGY.accountLaneCount()))
                    .isInstanceOf(ArithmeticException.class);
            assertThat(runtime.nextLiquidationId()).isEqualTo(Long.MAX_VALUE - 1);
            assertThat(RuntimeStateMaterializer.materialize(runtime, ids).riskState().liquidations()).isEmpty();
        }
    }

    private static int run(TradingRuntimeState runtime, RuntimeIdentityRegistry ids, PositionUserIndex index, int budget) {
        runtime.enterAsynchronousCommandScope();
        try {
            var work = new RiskScanCoordinator(budget, index, runtime, ids);
            await(work::poll);
            return work.completedWork();
        } finally { runtime.exitAsynchronousCommandScope(); }
    }

    private static ApplyMarkPriceCommand mark(long sequence, long price) {
        return new ApplyMarkPriceCommand(SYMBOL, 1, price, sequence, 1_700_000_000_000L + sequence);
    }

    private static TradingCoreState source(CoreMarginMode margin, int usersPerLane) {
        var reducer = new TradingCoreReducer(TOPOLOGY);
        var state = reducer.upsertInstrument(TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL),
                new UpsertInstrumentCommand(SYMBOL, 1, ContractType.LINEAR_PERPETUAL.ordinal(),
                        "BTC", "USDT", "USDT", 1, 1, 1, 100_000, 100_000, 0, 0, 0, -1, 0));
        int[] counts = new int[TOPOLOGY.accountLaneCount()];
        int required = counts.length * usersPerLane;
        for (long userId = 1; required > 0; userId++) {
            int lane = TOPOLOGY.accountLaneId(userId);
            if (counts[lane] == usersPerLane) continue;
            counts[lane]++;
            required--;
            state = reducer.adjustBalance(state, userId, new BalanceAdjustmentCommand("USDT", 100));
            var position = new CorePositionState(SYMBOL, "USDT", margin, CorePositionSide.NET,
                    1, 10, 100, 1_000, 0, 100);
            var users = new TreeMap<>(state.users());
            var user = state.user(userId);
            users.put(userId, new CoreUserState(state.productLine(), userId, user.revision() + 1,
                    Map.of("USDT", new AssetBalance("USDT", 0, 100)), user.reservations(),
                    Map.of(position.key(), position)));
            state = new TradingCoreState(state.productLine(), state.revision() + 1, users, state.orders(),
                    state.instruments(), state.riskState(), state.treasuryState());
        }
        return state;
    }

    private static void await(BooleanSupplier done) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!done.getAsBoolean()) {
            if (System.nanoTime() >= deadline) throw new AssertionError("risk task timeout");
            Thread.onSpinWait();
        }
    }
}
