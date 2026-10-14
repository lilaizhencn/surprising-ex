package com.surprising.aeron.service.state;

import com.surprising.aeron.service.state.risk.*;
import com.surprising.aeron.service.state.index.ActiveOrderIndex;
import com.surprising.aeron.service.state.index.AlgoOrderIndex;
import com.surprising.aeron.service.state.index.CancelAllAfterIndex;
import com.surprising.aeron.service.state.index.LiquidationIndex;
import com.surprising.aeron.service.state.index.TriggerOrderIndex;

import com.surprising.aeron.service.state.model.CoreLiquidationState;
import com.surprising.aeron.service.state.model.CoreRiskStatus;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.product.api.ProductLine;
import org.junit.jupiter.api.Test;

class RuntimeChangedIndexCommitTest {

    @Test
    void closeReopenAndPublicationRecyclingKeepZeroTotalsOutOfQueries() {
        var initial = TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL);
        var identities = new RuntimeIdentityRegistry();
        try (var runtime = RuntimeStateProjector.project(initial, identities)) {
            int symbol = identities.symbolId("1"), asset = identities.assetId("USDT");
            long key = identities.positionKey(7, "1:NET");
            var indexes = indexes(initial, identities);
            for (long quantity : new long[]{2, -2, 0, 3, 0}) {
                runtime.putPosition(key, new PositionRuntime(7, symbol, asset, CoreMarginMode.CROSS,
                        CorePositionSide.NET, CoreStateTestFixtures.runtimeInstrument(), quantity,
                        quantity == 0 ? 0 : 100, Math.abs(quantity) * 100, 0, Math.abs(quantity) * 10));
                indexes.coordinator.applyCurrent(runtime, identities);
                runtime.clearChangedKeys();
                assertThat(indexes.openInterest.openInterestSteps("1")).isEqualTo(Math.abs(quantity));
                assertThat(indexes.openInterest.longQuantity("1")).isEqualTo(Math.max(quantity, 0));
                assertThat(indexes.openInterest.shortQuantity("1")).isEqualTo(Math.max(-quantity, 0));
                if (quantity == 0) assertThat(indexes.openInterest.totals()).isEmpty();
                else assertThat(indexes.openInterest.totals()).containsOnlyKeys("1");
                assertThat(indexes.positionUsers.users("1")).containsExactly(7L);
            }
            runtime.removePosition(key, 7);
            indexes.coordinator.applyCurrent(runtime, identities);
            assertThat(indexes.openInterest.totals()).isEmpty();
            assertThat(indexes.positionUsers.users("1")).isEmpty();
        }
    }

    @Test
    void appliesAuthoritativeChangedIdsWithoutFactFrame() {
        TradingCoreState initial = TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL);
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        TradingRuntimeState runtime = RuntimeStateProjector.project(initial, identities);
        int symbolId = identities.symbolId("1");
        int assetId = identities.assetId("USDT");
        long positionKey = identities.positionKey(7, "1:NET");
        runtime.putPosition(positionKey, new PositionRuntime(7, symbolId, assetId,
                CoreMarginMode.CROSS, CorePositionSide.NET, CoreStateTestFixtures.runtimeInstrument(),
                2, 100, 200, 0, 40));
        runtime.putOrder(CoreStateTestFixtures.order(11, 7, symbolId, 2));

        IndexSet indexes = indexes(initial, identities);
        if (runtime.accountLanesStarted) LedgerControlTestSupport.await(runtime, runtime::pollControlLanes);
        indexes.coordinator.applyCurrent(runtime, identities);

        assertThat(indexes.activeOrders.ids()).containsExactly(11L);
        assertThat(indexes.positionUsers.users("1")).containsExactly(7L);
        assertThat(indexes.openInterest.openInterestSteps("1")).isEqualTo(2);

        runtime.clearChangedKeys();
        runtime.putPosition(positionKey, new PositionRuntime(7, symbolId, assetId,
                CoreMarginMode.CROSS, CorePositionSide.NET, CoreStateTestFixtures.runtimeInstrument(),
                3, 100, 300, 0, 60));
        runtime.putOrder(CoreStateTestFixtures.order(11, 7, symbolId, 5));
        if (runtime.accountLanesStarted) LedgerControlTestSupport.await(runtime, runtime::pollControlLanes);
        indexes.coordinator.applyCurrent(runtime, identities);

        assertThat(runtime.admissionOrders(7, symbolId).inspect(7, "1", CorePositionSide.NET,
                com.surprising.aeron.protocol.CoreOrderSide.BUY, CoreMarginMode.ISOLATED).pendingQuantity()).isEqualTo(5);
        assertThat(indexes.openInterest.openInterestSteps("1")).isEqualTo(3);

        runtime.clearChangedKeys();
        runtime.removeOrder(11);
        runtime.removePosition(positionKey, 7);
        if (runtime.accountLanesStarted) LedgerControlTestSupport.await(runtime, runtime::pollControlLanes);
        indexes.coordinator.applyCurrent(runtime, identities);

        assertThat(indexes.activeOrders.ids()).isEmpty();
        assertThat(indexes.positionUsers.users("1")).isEmpty();
        assertThat(indexes.openInterest.openInterestSteps("1")).isZero();
        runtime.close();
    }

    @Test
    void commitsLanePublishedRiskValuesAndReleasesRetiredPositionIdentity() {
        TradingCoreState initial = TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL);
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        TradingRuntimeState runtime = RuntimeStateProjector.project(initial, identities);
        int symbolId = identities.symbolId("1");
        int assetId = identities.assetId("USDT");
        long userId = 7;
        long positionKey = identities.positionKey(userId, "1:NET");
        runtime.putPosition(positionKey, new PositionRuntime(userId, symbolId, assetId,
                CoreMarginMode.CROSS, CorePositionSide.NET, CoreStateTestFixtures.runtimeInstrument(),
                2, 100, 200, 0, 40));
        runtime.clearChangedKeys();
        runtime.startAccountLanes();

        IndexSet indexes = indexes(initial, identities);
        RiskSnapshotRuntime risk = new RiskSnapshotRuntime(userId, symbolId, CorePositionSide.NET,
                9, 1_000, 20, 100, 100_000, CoreRiskStatus.NORMAL);
        runtime.dispatchControlLanes(runtime.topology().accountLaneMask(userId), ignored -> {
            runtime.putRiskSnapshot(positionKey, risk);
            return null;
        });
        if (runtime.accountLanesStarted) LedgerControlTestSupport.await(runtime, runtime::pollControlLanes);
        indexes.coordinator.applyCurrent(runtime, identities);

        assertThat(AccountLaneQueryTestSupport.read(runtime, userId, () -> runtime.riskSnapshot(positionKey))).isEqualTo(risk);

        runtime.clearChangedKeys();
        runtime.dispatchControlLanes(runtime.topology().accountLaneMask(userId), ignored -> {
            runtime.removeRiskSnapshot(positionKey);
            runtime.removePosition(positionKey, userId);
            return null;
        });
        if (runtime.accountLanesStarted) LedgerControlTestSupport.await(runtime, runtime::pollControlLanes);
        indexes.coordinator.applyCurrent(runtime, identities);
        runtime.clearCommittedChanges(identities);

        assertThat(AccountLaneQueryTestSupport.read(runtime, userId, () -> runtime.riskSnapshot(positionKey))).isNull();
        assertThat(identities.findPositionKey(userId, "1:NET")).isNull();
        assertThat(runtime.snapshotProjectionStateDirty()).isFalse();
        runtime.close();
    }

    @Test
    void commitsLanePublishedLiquidationValuesAndDeletion() {
        TradingCoreState initial = TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL);
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        TradingRuntimeState runtime = RuntimeStateProjector.project(initial, identities);
        int symbolId = identities.symbolId("1");
        long userId = 7;
        LiquidationRuntime planned = new LiquidationRuntime(1, userId, symbolId,
                CoreMarginMode.CROSS, CorePositionSide.NET, CoreStateTestFixtures.runtimeInstrument(), 9, 2, 2,
                0, 0, 0, 0, CoreLiquidationState.Status.PLANNED, 0);
        runtime.startAccountLanes();
        IndexSet indexes = indexes(initial, identities);

        runtime.dispatchControlLanes(runtime.topology().accountLaneMask(userId), ignored -> {
            runtime.putLiquidation(planned);
            return null;
        });
        if (runtime.accountLanesStarted) LedgerControlTestSupport.await(runtime, runtime::pollControlLanes);
        indexes.coordinator.applyCurrent(runtime, identities);

        assertThat(AccountLaneQueryTestSupport.read(runtime, userId, () -> runtime.liquidation(1))).isEqualTo(planned);
        assertThat(indexes.liquidations.activeIds()).containsExactly(1L);

        runtime.clearChangedKeys();
        runtime.dispatchControlLanes(runtime.topology().accountLaneMask(userId), ignored -> {
            runtime.removeLiquidation(1);
            return null;
        });
        if (runtime.accountLanesStarted) LedgerControlTestSupport.await(runtime, runtime::pollControlLanes);
        indexes.coordinator.applyCurrent(runtime, identities);

        assertThat(AccountLaneQueryTestSupport.read(runtime, userId, () -> runtime.liquidation(1))).isNull();
        assertThat(indexes.liquidations.activeIds()).isEmpty();
        runtime.close();
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
        return new IndexSet(positionUsers, openInterest, activeOrders, liquidations,
                new RuntimeFactIndexes(positionUsers, openInterest, triggers, algos, liquidations,
                        timers, activeOrders, adlPositions));
    }

    private record IndexSet(PositionUserIndex positionUsers, OpenInterestIndex openInterest,
                            ActiveOrderIndex activeOrders, LiquidationIndex liquidations,
                            RuntimeFactIndexes coordinator) { }
}
