package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.state.account.UserRuntime;
import com.surprising.aeron.service.state.model.CoreAlgoOrderState;
import com.surprising.aeron.service.state.model.CoreLiquidationState;
import com.surprising.aeron.service.state.model.CoreRiskStatus;
import com.surprising.aeron.service.state.model.CoreTriggerOrderState;
import com.surprising.aeron.service.state.risk.RiskSnapshotRuntime;
import com.surprising.product.api.ProductLine;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LaneColdStateAuthorityTest {
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void internalReadsFollowTheOwningLaneWithoutAnOwnerEntityCache(int laneId) {
        try (var runtime = new TradingRuntimeState(LaneTopology.productionDefault())) {
            long userId = userInLane(runtime, laneId);
            runtime.putUser(new UserRuntime(userId));
            var liquidation = liquidation(userId);
            var risk = risk(userId);
            var algo = algo(userId);
            var trigger = trigger(userId);
            runtime.putLiquidation(liquidation);
            runtime.putRiskSnapshot(101, risk);
            runtime.putAlgoOrder(algo);
            runtime.putTriggerOrder(trigger);
            runtime.clearChangedKeys();

            assertThat(runtime.liquidation(100)).isSameAs(liquidation);
            assertThat(runtime.riskSnapshot(101)).isSameAs(risk);
            assertThat(runtime.algoOrder(102)).isSameAs(algo);
            assertThat(runtime.triggerOrder(103)).isSameAs(trigger);
            assertThat(runtime.algoOrdersForRuntime()).containsEntry(102L, algo);
            assertThat(runtime.hasUnresolvedLiquidation(0)).isTrue();

            runtime.onLane(laneId, lane -> {
                lane.cold.liquidations.remove(100);
                TradingRuntimeState.removeActiveLiquidation(lane, liquidation);
                lane.cold.riskSnapshots.remove(101);
                lane.cold.algoOrders.remove(102);
                lane.removeTrigger(103);
                return null;
            });
            // A Lane removal has no second published map to invalidate.
            assertThat(runtime.liquidation(100)).isNull();
            assertThat(runtime.riskSnapshot(101)).isNull();
            assertThat(runtime.algoOrder(102)).isNull();
            assertThat(runtime.triggerOrder(103)).isNull();
            assertThat(runtime.algoOrdersForRuntime()).isEmpty();
            assertThat(runtime.hasUnresolvedLiquidation(0)).isFalse();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void aRejectedCommandRestoresColdStateAndItsLaneIndexes(int laneId) {
        try (var runtime = new TradingRuntimeState(LaneTopology.productionDefault())) {
            long userId = userInLane(runtime, laneId);
            runtime.putUser(new UserRuntime(userId));
            var liquidation = liquidation(userId);
            var risk = risk(userId);
            var algo = algo(userId);
            var trigger = trigger(userId);
            runtime.putLiquidation(liquidation);
            runtime.putRiskSnapshot(101, risk);
            runtime.putAlgoOrder(algo);
            runtime.putTriggerOrder(trigger);
            runtime.clearChangedKeys();
            long beforeRevision = runtime.revision();

            runtime.removeLiquidation(100);
            runtime.removeRiskSnapshot(101);
            runtime.removeAlgoOrder(102);
            runtime.removeTriggerOrder(103);
            assertThat(runtime.hasActiveLiquidationConflict(userId, 0, 0)).isFalse();
            assertThat(runtime.hasTriggerClient(userId, trigger.clientTriggerOrderId())).isFalse();

            runtime.rollbackActiveCommand(beforeRevision, 1);

            assertThat(runtime.liquidation(100)).isSameAs(liquidation);
            assertThat(runtime.riskSnapshot(101)).isSameAs(risk);
            assertThat(runtime.algoOrder(102)).isSameAs(algo);
            assertThat(runtime.triggerOrder(103)).isSameAs(trigger);
            assertThat(runtime.hasActiveLiquidationConflict(userId, 0, 0)).isTrue();
            assertThat(runtime.hasTriggerClient(userId, trigger.clientTriggerOrderId())).isTrue();
        }
    }

    private static long userInLane(TradingRuntimeState runtime, int laneId) {
        long userId = 1;
        while (runtime.topology().accountLaneId(userId) != laneId) userId++;
        return userId;
    }

    private static LiquidationRuntime liquidation(long userId) {
        return new LiquidationRuntime(100, userId, 0, CoreMarginMode.CROSS, CorePositionSide.NET,
                CoreStateTestFixtures.runtimeInstrument(), 1, 10, 10, 0, 100, 100, 0,
                CoreLiquidationState.Status.PLANNED, 0);
    }

    private static RiskSnapshotRuntime risk(long userId) {
        return new RiskSnapshotRuntime(userId, 0, CorePositionSide.NET,
                1, 10, 0, 1, 100_000, CoreRiskStatus.NORMAL);
    }

    private static CoreAlgoOrderState algo(long userId) {
        return new CoreAlgoOrderState(102, userId, "algo-102", "1", 0, CoreOrderSide.BUY,
                100, 10, 1, 10, 60, CoreMarginMode.CROSS, CorePositionSide.NET,
                false, false, CoreTimeInForce.GTC, 0, 0, "", "", 1, 1, 0, 1, 1, 1, List.of());
    }

    private static CoreTriggerOrderState trigger(long userId) {
        return new CoreTriggerOrderState(103, ProductLine.LINEAR_PERPETUAL, userId, "trigger-103", "",
                "1", CoreStateTestFixtures.runtimeInstrument(), CoreOrderSide.BUY,
                CoreTriggerOrderType.STOP_LOSS, CoreTriggerCondition.GREATER_OR_EQUAL,
                100, 0, 0, 0, 0, 0, CoreOrderType.LIMIT, CoreTimeInForce.GTC, 90, 1,
                CoreMarginMode.CROSS, CorePositionSide.NET, CoreTriggerOrderStatus.PENDING,
                0, 0, 0, "", "", 0, 0, 1, 1, 1);
    }
}
