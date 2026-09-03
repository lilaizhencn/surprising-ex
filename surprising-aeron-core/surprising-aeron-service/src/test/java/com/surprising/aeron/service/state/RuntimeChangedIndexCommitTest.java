package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.product.api.ProductLine;
import org.junit.jupiter.api.Test;

class RuntimeChangedIndexCommitTest {

    @Test
    void appliesAuthoritativeChangedIdsWithoutFactFrame() {
        TradingCoreState initial = TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL);
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        TradingRuntimeState runtime = RuntimeStateProjector.project(initial, identities);
        int symbolId = identities.symbolId("BTC-USDT");
        int assetId = identities.assetId("USDT");
        long positionKey = identities.positionKey(7, "BTC-USDT:NET");
        runtime.putPosition(positionKey, new PositionRuntime(7, symbolId, assetId,
                CoreMarginMode.CROSS, CorePositionSide.NET, 1, 2, 100, 200, 0, 40));
        runtime.putOrder(new OrderRuntime(11, 7, symbolId, 2));

        IndexSet indexes = indexes(initial, identities);
        indexes.coordinator.applyCurrent(runtime, identities);

        assertThat(indexes.activeOrders.ids()).containsExactly(11L);
        assertThat(indexes.positionUsers.users("BTC-USDT")).containsExactly(7L);
        assertThat(indexes.openInterest.openInterestSteps("BTC-USDT")).isEqualTo(2);

        runtime.clearChangedKeys();
        runtime.putPosition(positionKey, new PositionRuntime(7, symbolId, assetId,
                CoreMarginMode.CROSS, CorePositionSide.NET, 1, 3, 100, 300, 0, 60));
        runtime.putOrder(new OrderRuntime(11, 7, symbolId, 5));
        indexes.coordinator.applyCurrent(runtime, identities);

        assertThat(indexes.activeOrders.pendingQuantity(
                7, "BTC-USDT", CorePositionSide.NET,
                com.surprising.aeron.protocol.CoreOrderSide.BUY)).isEqualTo(5);
        assertThat(indexes.openInterest.openInterestSteps("BTC-USDT")).isEqualTo(3);

        runtime.clearChangedKeys();
        runtime.removeOrder(11);
        runtime.removePosition(positionKey, 7);
        indexes.coordinator.applyCurrent(runtime, identities);

        assertThat(indexes.activeOrders.ids()).isEmpty();
        assertThat(indexes.positionUsers.users("BTC-USDT")).isEmpty();
        assertThat(indexes.openInterest.openInterestSteps("BTC-USDT")).isZero();
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
        RiskSnapshotIndex riskSnapshots = new RiskSnapshotIndex(state);
        return new IndexSet(positionUsers, openInterest, activeOrders,
                new RuntimeFactIndexes(positionUsers, openInterest, triggers, algos, liquidations,
                        timers, activeOrders, adlPositions, riskSnapshots));
    }

    private record IndexSet(PositionUserIndex positionUsers, OpenInterestIndex openInterest,
                            ActiveOrderIndex activeOrders, RuntimeFactIndexes coordinator) { }
}
