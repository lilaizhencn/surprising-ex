package com.surprising.aeron.service.state;

import com.surprising.aeron.service.state.account.UserRuntime;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ControlLaneDispatcherTest {
    @Test
    void controlTasksExecuteDirectlyOnSpecifiedLanes() {
        try (var runtime = new TradingRuntimeState(LaneTopology.productionDefault())) {
            runtime.startAccountLanes();
            runtime.dispatchControlLanes(3, lane -> lane + 10);
            assertThat(runtime.pollControlLanes()).isTrue();
            assertThat(runtime.controlLaneResult(0)).isEqualTo(10);
            assertThat(runtime.controlLaneResult(1)).isEqualTo(11);
        }
    }

    @Test
    void reservationAndAccountStateAccessibleAfterDispatch() {
        try (var runtime = new TradingRuntimeState()) {
            runtime.putUser(new UserRuntime(7));
            runtime.putBalance(new com.surprising.aeron.service.state.account.BalanceRuntime(7, 3, 1000, 0));
            runtime.clearChangedKeys();
            runtime.startAccountLanes();
            int laneId = runtime.topology().accountLaneId(7);
            runtime.dispatchControlLanes(1L << laneId, ignored -> {
                CoreStateTestFixtures.reserveOrder(runtime, 11, 7, 91, 5, 2, 3, 200);
                return null;
            });
            assertThat(runtime.order(11)).isNotNull();
            assertThat(runtime.balance(7, 3).availableUnits()).isEqualTo(800);
            assertThat(runtime.reservation(11).reservedUnits()).isEqualTo(200);
        }
    }

    @Test
    void invalidDispatchThrowsIllegalStateException() {
        try (var runtime = new TradingRuntimeState(LaneTopology.productionDefault())) {
            runtime.startAccountLanes();
            assertThatThrownBy(() -> runtime.dispatchControlLanes(0, lane -> lane))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> runtime.dispatchControlLanes(-1L, lane -> lane))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
