package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CoreRiskLimitBracket;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.List;
import org.junit.jupiter.api.Test;

class TradingRuntimeStateIndexTest {

    @Test
    void keepsPositionKeysOrderedByUserAndSymbol() {
        TradingRuntimeState runtime = new TradingRuntimeState();
        runtime.putPosition(20, position(7, 1));
        runtime.putPosition(10, position(7, 1));
        runtime.putPosition(30, position(8, 1));

        assertThat(runtime.positionKeysForUserAndSymbol(7, 1)).containsExactly(10L, 20L);

        runtime.removePosition(10, 7);
        assertThat(runtime.positionKeysForUserAndSymbol(7, 1)).containsExactly(20L);
    }

    @Test
    void typedOwnerGroupsUpdateEveryIndexExactlyOncePerOwnerGroup() throws Exception {
        TradingCoreState before = new TradingCoreReducer().adjustBalance(
                TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL), 7,
                new BalanceAdjustmentCommand("USDT", 10_000));
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities);
        runtime.putInstrument(instrument());
        long secondUser = userInDifferentLane(runtime, 7);
        runtime.putUser(new UserRuntime(secondUser));
        int symbolId = identities.symbolId("BTC-USDT");
        int assetId = identities.assetId("USDT");
        addIndexedOwnerGroup(runtime, identities, 7, symbolId, assetId, 10);
        addIndexedOwnerGroup(runtime, identities, secondUser, symbolId, assetId, 20);
        TradingCoreState current = RuntimeStateMaterializer.materialize(runtime, identities);
        RuntimeCommitPatch patch = capture(runtime, identities, before, current, 1);
        RuntimeProjectionState projection = new RuntimeProjectionState(before,
                before.businessStateHash(), RollingFundsStateHash.compute(before));
        projection.apply(patch);
        TradingCoreState after = projection.freeze(patch.sequence());
        IndexSet incremental = indexes(before, identities);
        RuntimeCommitIndexes commitIndexes = incremental.coordinator();

        commitIndexes.apply(patch);

        IndexSet rebuilt = indexes(after, identities);
        assertThat(incremental.positionUsers().users("BTC-USDT"))
                .isEqualTo(rebuilt.positionUsers().users("BTC-USDT"));
        assertThat(incremental.openInterest().totals()).isEqualTo(rebuilt.openInterest().totals());
        assertThat(incremental.triggers().ids()).isEqualTo(rebuilt.triggers().ids());
        assertThat(incremental.algos().containsClient(7, "algo-17"))
                .isEqualTo(rebuilt.algos().containsClient(7, "algo-17"));
        assertThat(incremental.liquidations().activeIds()).isEqualTo(rebuilt.liquidations().activeIds());
        assertThat(incremental.timers().query(7, "BTC-USDT", 2_000, 10, after.cancelAllAfterTimers()))
                .isEqualTo(rebuilt.timers().query(7, "BTC-USDT", 2_000, 10,
                        after.cancelAllAfterTimers()));
        assertThat(incremental.activeOrders().ids()).isEqualTo(rebuilt.activeOrders().ids());
        assertThat(incremental.adlPositions().positions("USDT"))
                .isEqualTo(rebuilt.adlPositions().positions("USDT"));
        assertThat(incremental.riskSnapshots().keys()).isEqualTo(rebuilt.riskSnapshots().keys());
        assertParity(incremental, rebuilt, current, secondUser);
        assertThat(commitIndexes.lastApplyStats()).isEqualTo(
                new RuntimeCommitIndexes.ApplyStats(2, 2, 2, 2, 2, 2, 2, 2, 2));

        runtime.clearChangedKeys();
        removeIndexedOwnerGroup(runtime, identities, 7, 10);
        removeIndexedOwnerGroup(runtime, identities, secondUser, 20);
        TradingCoreState removed = RuntimeStateMaterializer.materialize(runtime, identities);
        RuntimeCommitPatch removal = capture(runtime, identities, current, removed, 2);
        commitIndexes.apply(removal);
        IndexSet rebuiltRemoved = indexes(removed, identities);
        assertParity(incremental, rebuiltRemoved, removed, secondUser);
        assertThat(commitIndexes.lastApplyStats()).isEqualTo(
                new RuntimeCommitIndexes.ApplyStats(2, 2, 2, 2, 2, 2, 2, 2, 2));

        commitIndexes.rebuild(current, identities);
        IndexSet rebuiltRollback = indexes(current, identities);
        assertParity(incremental, rebuiltRollback, current, secondUser);
        assertThat(commitIndexes.lastApplyStats()).isEqualTo(
                new RuntimeCommitIndexes.ApplyStats(0, 0, 0, 0, 0, 0, 0, 0, 0));
    }

    private static void addIndexedOwnerGroup(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                             long userId, int symbolId, int assetId, long idBase) {
        long positionKey = identities.positionKey(userId, "BTC-USDT");
        runtime.putPosition(positionKey, position(userId, symbolId, assetId));
        runtime.putOrder(new OrderRuntime(idBase + 1, userId, symbolId, 2));
        runtime.putLiquidation(new LiquidationRuntime(idBase + 3, userId, symbolId, CoreMarginMode.CROSS,
                CorePositionSide.NET, 1, 1, 2, 2, 0, 0, 0, 0,
                CoreLiquidationState.Status.PLANNED, 0));
        runtime.putRiskSnapshot(positionKey, new RiskSnapshotRuntime(userId, symbolId, CorePositionSide.NET,
                1, 10_000, 0, 10, 1_000, CoreRiskStatus.NORMAL));
        runtime.putAlgoOrder(algoOrder(idBase + 7, userId));
        runtime.putTriggerOrder(triggerOrder(idBase + 9, userId));
        CoreCancelAllAfterState timer = new CoreCancelAllAfterState(userId, "BTC-USDT", 1_000 + idBase,
                com.surprising.aeron.protocol.CoreCancelAllAfterStatus.ACTIVE, 2_000, 1_000, 0, 0, 1);
        runtime.putCancelAllAfterTimer(timer.key(), timer);
    }

    private static CoreInstrumentState instrument() {
        return new CoreInstrumentState("BTC-USDT", 1, ContractType.LINEAR_PERPETUAL,
                "BTC", "USDT", "USDT", 1, 1, 1_000_000,
                100_000, 50_000, 0, 0, 0, null, 0,
                10_000_000, Long.MAX_VALUE, 0, 1,
                List.of(new CoreRiskLimitBracket(1, 0, Long.MAX_VALUE,
                        10_000_000, 100_000, 50_000)));
    }

    private static RuntimeCommitPatch capture(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                              TradingCoreState before, TradingCoreState after, long sequence) {
        TradingRuntimeState.PreparedCommit prepared = runtime.prepareCommitPatch(
                sequence, sequence - 1, sequence, identities,
                before.revision(), com.surprising.aeron.protocol.CoreMatcherTransition.unchanged(0, 0), 0,
                before.businessStateHash(), after.businessStateHash(),
                RollingFundsStateHash.compute(before), RollingFundsStateHash.compute(after), true);
        RuntimeCommitPatch.PreparedChanges changes = prepared.prepareChanges();
        return prepared.seal(changes, after.businessStateHash(), RollingFundsStateHash.compute(after));
    }

    private static void removeIndexedOwnerGroup(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                                long userId, long idBase) {
        runtime.removePosition(identities.positionKey(userId, "BTC-USDT"), userId);
        runtime.removeOrder(idBase + 1);
        runtime.removeLiquidation(idBase + 3);
        runtime.removeAlgoOrder(idBase + 7);
        runtime.removeTriggerOrder(idBase + 9);
        runtime.removeRiskSnapshot(identities.positionKey(userId, "BTC-USDT"));
        CoreCancelAllAfterState disabled = new CoreCancelAllAfterState(userId, "BTC-USDT", 0,
                com.surprising.aeron.protocol.CoreCancelAllAfterStatus.DISABLED, 0, 2_000, 0, 0, 2);
        runtime.putCancelAllAfterTimer(disabled.key(), disabled);
    }

    private static void assertParity(IndexSet actual, IndexSet rebuilt, TradingCoreState state, long secondUser) {
        assertThat(actual.positionUsers().users("BTC-USDT"))
                .isEqualTo(rebuilt.positionUsers().users("BTC-USDT"));
        assertThat(actual.openInterest().totals()).isEqualTo(rebuilt.openInterest().totals());
        assertThat(actual.triggers().ids()).isEqualTo(rebuilt.triggers().ids());
        assertThat(actual.algos().containsClient(7, "algo-17"))
                .isEqualTo(rebuilt.algos().containsClient(7, "algo-17"));
        assertThat(actual.algos().containsClient(secondUser, "algo-27"))
                .isEqualTo(rebuilt.algos().containsClient(secondUser, "algo-27"));
        assertThat(actual.liquidations().activeIds()).isEqualTo(rebuilt.liquidations().activeIds());
        assertThat(actual.timers().query(7, "BTC-USDT", 2_000, 10, state.cancelAllAfterTimers()))
                .isEqualTo(rebuilt.timers().query(7, "BTC-USDT", 2_000, 10,
                        state.cancelAllAfterTimers()));
        assertThat(actual.activeOrders().ids()).isEqualTo(rebuilt.activeOrders().ids());
        assertThat(actual.adlPositions().positions("USDT"))
                .isEqualTo(rebuilt.adlPositions().positions("USDT"));
        assertThat(actual.riskSnapshots().keys()).isEqualTo(rebuilt.riskSnapshots().keys());
    }

    private static long userInDifferentLane(TradingRuntimeState runtime, long firstUser) {
        int firstLane = runtime.accountLane(firstUser).laneId();
        for (long userId = firstUser + 1; userId < 10_000; userId++) {
            if (runtime.accountLane(userId).laneId() != firstLane) return userId;
        }
        throw new AssertionError("no user routed to another owner lane");
    }

    private static PositionRuntime position(long userId, int symbolId) {
        return position(userId, symbolId, 1);
    }

    private static PositionRuntime position(long userId, int symbolId, int assetId) {
        return new PositionRuntime(userId, symbolId, assetId, CoreMarginMode.CROSS, CorePositionSide.NET,
                1, 1, 100, 100, 0, 0);
    }

    private static CoreAlgoOrderState algoOrder(long id, long userId) {
        return new CoreAlgoOrderState(id, userId, "algo-" + id, "BTC-USDT", 1, CoreOrderSide.BUY,
                100, 2, 1, 1, 10, CoreMarginMode.CROSS, CorePositionSide.NET,
                false, false, CoreTimeInForce.GTC, 1, 0, "", "trace", 1, 1, 0, 1, 1, 1, List.of());
    }

    private static CoreTriggerOrderState triggerOrder(long id, long userId) {
        return new CoreTriggerOrderState(id, ProductLine.LINEAR_PERPETUAL, userId, "trigger-" + id, "",
                "BTC-USDT", CoreOrderSide.BUY,
                com.surprising.aeron.protocol.CoreTriggerOrderType.STOP_LOSS,
                com.surprising.aeron.protocol.CoreTriggerCondition.GREATER_OR_EQUAL,
                100, 0, 0, 0, 0, 0, CoreOrderType.MARKET, CoreTimeInForce.IOC,
                0, 1, CoreMarginMode.CROSS, CorePositionSide.NET,
                com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING,
                0, 0, 0, "", "trace", 0, 0, 1, 1, 1, 1, 0, 0);
    }

    private static IndexSet indexes(TradingCoreState state, RuntimeIdentityRegistry identities) {
        PositionUserIndex positionUsers = new PositionUserIndex(state, identities);
        OpenInterestIndex openInterest = new OpenInterestIndex(state, identities);
        TriggerOrderIndex triggers = new TriggerOrderIndex(state);
        AlgoOrderIndex algos = new AlgoOrderIndex(state);
        LiquidationIndex liquidations = new LiquidationIndex(state);
        CancelAllAfterIndex timers = new CancelAllAfterIndex(state);
        ActiveOrderIndex activeOrders = new ActiveOrderIndex(state, identities);
        AdlPositionIndex adlPositions = new AdlPositionIndex(state, identities);
        RiskSnapshotIndex riskSnapshots = new RiskSnapshotIndex(state);
        return new IndexSet(positionUsers, openInterest, triggers, algos, liquidations, timers,
                activeOrders, adlPositions, riskSnapshots,
                new RuntimeCommitIndexes(positionUsers, openInterest, triggers, algos, liquidations,
                        timers, activeOrders, adlPositions, riskSnapshots));
    }

    private record IndexSet(PositionUserIndex positionUsers, OpenInterestIndex openInterest,
                            TriggerOrderIndex triggers, AlgoOrderIndex algos,
                            LiquidationIndex liquidations, CancelAllAfterIndex timers,
                            ActiveOrderIndex activeOrders, AdlPositionIndex adlPositions,
                            RiskSnapshotIndex riskSnapshots, RuntimeCommitIndexes coordinator) { }
}
