package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.protocol.ApplyMarkPriceCommand;
import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.RegisterInstrumentCommand;
import com.surprising.aeron.service.lane.LaneTopology;
import com.surprising.aeron.service.state.account.BalanceRuntime;
import com.surprising.aeron.service.state.account.CoreUserState;
import com.surprising.aeron.service.state.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.state.index.AdlPositionIndex;
import com.surprising.aeron.service.state.index.OpenInterestIndex;
import com.surprising.aeron.service.state.index.PositionUserIndex;
import com.surprising.aeron.service.state.index.RuntimeFactIndexes;
import com.surprising.aeron.service.state.model.*;
import com.surprising.aeron.service.state.model.AssetBalance;
import com.surprising.aeron.service.state.model.CorePositionState;
import com.surprising.aeron.service.state.order.RuntimeTestStateTransitions;
import com.surprising.aeron.service.state.risk.RiskScanCoordinator;
import com.surprising.aeron.service.state.risk.RuntimeDerivativeRiskProcessor;
import com.surprising.aeron.service.state.snapshot.*;
import com.surprising.aeron.service.state.snapshot.RuntimeStateMaterializer;
import com.surprising.aeron.service.state.snapshot.RuntimeStateProjector;
import com.surprising.aeron.service.state.snapshot.TradingStateSnapshotCodec;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class ParallelRiskScanTest {
    private static final LaneTopology TOPOLOGY = LaneTopology.productionDefault();
    private static final String SYMBOL = "1";

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {1, 3, 7})
    void ledgerUserPagesKeepSmallRiskBudgetsCompleteWithoutReadingIndexesOnLanes(int budget) {
        var source = source(CoreMarginMode.ISOLATED, 12);
        var ids = new RuntimeIdentityRegistry();
        try (var runtime = RuntimeStateProjector.project(source, ids, TOPOLOGY)) {
            var index = new PositionUserIndex(source, ids, TOPOLOGY);
            runtime.bindFactIndexes(new RuntimeFactIndexes(index, new OpenInterestIndex(source, ids),
                    new com.surprising.aeron.service.state.index.TriggerOrderIndex(source),
                    new com.surprising.aeron.service.state.index.AlgoOrderIndex(source),
                    new com.surprising.aeron.service.state.index.LiquidationIndex(source),
                    new com.surprising.aeron.service.state.index.CancelAllAfterIndex(source),
                    new com.surprising.aeron.service.state.index.ActiveOrderIndex(source, ids),
                    new AdlPositionIndex(source, ids)), ids);
            RuntimeDerivativeRiskProcessor.applyMarkPriceRuntime(mark(1, 80), runtime, ids);
            runtime.startAccountLanes(); runtime.startSystemLedgerLane(); runtime.enterAsynchronousCommandScope();
            try {
                var work = new RiskScanCoordinator(budget, index, runtime, ids);
                for (int page = 0; runtime.firstRiskIncompleteScan() != null; page++) {
                    assertThat(page).isLessThan(4096);
                    if (page != 0) work.resetForCommand(budget);
                    await(work::poll);
                    assertThat(work.completedWork()).isBetween(1, budget);
                }
            } finally { runtime.exitAsynchronousCommandScope(); }
            runtime.close();
            var complete = RuntimeStateMaterializer.materialize(runtime, ids);
            assertThat(complete.riskState().snapshots()).hasSize(source.users().size());
            assertThat(complete.riskState().liquidations()).hasSize(source.users().size());
            assertThat(complete.riskState().nextLiquidationId()).isEqualTo(source.users().size() + 1L);
            assertThat(complete.users()).isEqualTo(source.users());
            assertThat(complete.treasuryState()).isEqualTo(source.treasuryState());
        }
    }

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
            var work = new RiskScanCoordinator(TOPOLOGY.accountLaneCount(), index, runtime, ids);
            while (!work.poll()) {
                Thread.onSpinWait();
            }
            runtime.close();
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
    void closingAProfitablePositionBetweenPagesMustNotCountItsProfitTwice() {
        var reducer = new RuntimeTestStateTransitions(TOPOLOGY);
        var source = reducer.registerInstrument(source(CoreMarginMode.CROSS, 1),
                new RegisterInstrumentCommand("2", ContractType.LINEAR_PERPETUAL.ordinal(),
                        "ETH", "USDT", "USDT", 1, 1, 1, 100_000, 100_000, 0, 0, 0, -1, 0));
        var users = new TreeMap<>(source.users());
        for (var user : source.users().values()) {
            var positions = new TreeMap<>(user.positions());
            positions.put("2", new CorePositionState("2", "USDT", CoreMarginMode.CROSS,
                    CorePositionSide.NET, 1, 100, 100, 0, 0));
            users.put(user.userId(), new CoreUserState(source.productLine(), user.userId(), user.revision() + 1,
                    user.balances(), user.reservations(), positions));
        }
        source = new TradingCoreState(source.productLine(), source.revision() + 1, users, source.orders(),
                source.instruments(), source.riskState(), source.treasuryState());
        var ids = new RuntimeIdentityRegistry();
        try (var runtime = RuntimeStateProjector.project(source, ids, TOPOLOGY)) {
            RuntimeDerivativeRiskProcessor.applyMarkPriceRuntime(new ApplyMarkPriceCommand("2", 100, 1, 1_700_000_000_000L), runtime, ids);
            RuntimeDerivativeRiskProcessor.applyMarkPriceRuntime(mark(1, 120), runtime, ids);
            runtime.startAccountLanes();
            run(runtime, ids, new PositionUserIndex(source, ids, TOPOLOGY), TOPOLOGY.accountLaneCount());
            long userId = source.users().keySet().iterator().next();
            var scan = runtime.riskScan(ids.symbolId(SYMBOL));
            assertThat(scan.laneProgress().get(TOPOLOGY.accountLaneId(userId)).unrealizedPnlUnits()).isEqualTo(200);
            // 按平仓结算后的实际形态更新账户：200 盈利进入钱包，BTC 持仓消失，ETH 仍在。
            runtime.acquireOwnerLaneAccess();
            try {
                runtime.replaceBalance(new BalanceRuntime(userId, ids.assetId("USDT"), 300, 0));
                runtime.removePosition(ids.preparedPositionKey(userId, SYMBOL), userId);
                runtime.advanceUserRevision(userId);
            } finally {
                runtime.releaseOwnerLaneAccess();
            }
            var changed = RuntimeStateMaterializer.materialize(runtime, ids);
            var index = new PositionUserIndex(changed, ids, TOPOLOGY);
            RuntimeDerivativeRiskProcessor.applyContinuationRuntime(2 * TOPOLOGY.accountLaneCount(),
                    ids.symbolId(SYMBOL), index, runtime, ids);
            var state = RuntimeStateMaterializer.materialize(runtime, ids);
            assertThat(state.riskState().snapshots().get(userId + ":2").equityUnits()).isEqualTo(300);
        }
    }

    @Test
    void anotherSymbolsPriceChangeInvalidatesPartialPortfolioEvenAfterSnapshot() {
        var reducer = new RuntimeTestStateTransitions(TOPOLOGY);
        var source = reducer.registerInstrument(source(CoreMarginMode.CROSS, 1),
                new RegisterInstrumentCommand("2", ContractType.LINEAR_PERPETUAL.ordinal(),
                        "ETH", "USDT", "USDT", 1, 1, 1, 100_000, 100_000, 0, 0, 0, -1, 0));
        var users = new TreeMap<>(source.users());
        for (var user : source.users().values()) {
            var positions = new TreeMap<>(user.positions());
            positions.put("2", new CorePositionState("2", "USDT", CoreMarginMode.CROSS,
                    CorePositionSide.NET, 1, 100, 100, 0, 0));
            users.put(user.userId(), new CoreUserState(source.productLine(), user.userId(), user.revision() + 1,
                    user.balances(), user.reservations(), positions));
        }
        source = new TradingCoreState(source.productLine(), source.revision() + 1, users, source.orders(),
                source.instruments(), source.riskState(), source.treasuryState());
        var ids = new RuntimeIdentityRegistry();
        try (var runtime = RuntimeStateProjector.project(source, ids, TOPOLOGY)) {
            RuntimeDerivativeRiskProcessor.applyMarkPriceRuntime(mark(1, 100), runtime, ids);
            RuntimeDerivativeRiskProcessor.applyMarkPriceRuntime(new ApplyMarkPriceCommand("2", 100, 1, 1_700_000_000_001L), runtime, ids);
            var index = new PositionUserIndex(source, ids, TOPOLOGY);
            RuntimeDerivativeRiskProcessor.applyContinuationRuntime(2 * TOPOLOGY.accountLaneCount(),
                    ids.symbolId("2"), index, runtime, ids);
            assertThat(runtime.riskScan(ids.symbolId("2")).laneProgress())
                    .allMatch(cursor -> cursor.userId() > 0 && cursor.unrealizedPnlUnits() == 0);
            // ETH 扫描已经累计完两个持仓，此时 BTC 新价格只更新 BTC 扫描入口。
            RuntimeDerivativeRiskProcessor.applyMarkPriceRuntime(mark(2, 120), runtime, ids);
            var checkpoint = RuntimeStateMaterializer.materialize(runtime, ids);
            var restoredState = TradingStateSnapshotCodec.decode(TradingStateSnapshotCodec.encode(checkpoint),
                    ProductLine.LINEAR_PERPETUAL);
            var restoredIds = new RuntimeIdentityRegistry();
            try (var restored = RuntimeStateProjector.project(restoredState, restoredIds, TOPOLOGY)) {
                var restoredIndex = new PositionUserIndex(restoredState, restoredIds, TOPOLOGY);
                for (int page = 0; page < 8 && !runtime.riskScan(ids.symbolId("2")).riskComplete(); page++) {
                    RuntimeDerivativeRiskProcessor.applyContinuationRuntime(TOPOLOGY.accountLaneCount(),
                            ids.symbolId("2"), index, runtime, ids);
                    RuntimeDerivativeRiskProcessor.applyContinuationRuntime(TOPOLOGY.accountLaneCount(),
                            restoredIds.symbolId("2"), restoredIndex, restored, restoredIds);
                    assertThat(RuntimeStateMaterializer.materialize(restored, restoredIds))
                            .isEqualTo(RuntimeStateMaterializer.materialize(runtime, ids));
                }
                var complete = RuntimeStateMaterializer.materialize(runtime, ids);
                assertThat(runtime.riskScan(ids.symbolId("2")).riskComplete()).isTrue();
                assertThat(complete.riskState().snapshots().values()).hasSize(2 * TOPOLOGY.accountLaneCount())
                        .allMatch(snapshot -> snapshot.equityUnits() == 300);
                assertThat(complete.riskState().liquidations()).isEmpty();
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
            runtime.close();
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
        return new ApplyMarkPriceCommand(SYMBOL, price, sequence, 1_700_000_000_000L + sequence);
    }

    private static TradingCoreState source(CoreMarginMode margin, int usersPerLane) {
        var reducer = new RuntimeTestStateTransitions(TOPOLOGY);
        var state = reducer.registerInstrument(TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL),
                new RegisterInstrumentCommand(SYMBOL, ContractType.LINEAR_PERPETUAL.ordinal(),
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
                    10, 100, 1_000, 0, 100);
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
