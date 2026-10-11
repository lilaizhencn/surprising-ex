package com.surprising.aeron.service.state;

import com.surprising.aeron.service.state.account.UserRuntime;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ControlLaneDispatcherTest {
    @Test
    void controlTasksExecuteOnPermanentWorkersAndPublishOnlyAfterCompletion() throws Exception {
        try (var runtime = new TradingRuntimeState(LaneTopology.productionDefault())) {
            runtime.startAccountLanes();
            Thread owner = Thread.currentThread();
            var entered = new java.util.concurrent.CountDownLatch(2);
            var release = new java.util.concurrent.CountDownLatch(1);
            try {
                runtime.dispatchControlLanes(3, lane -> {
                    assertThat(Thread.currentThread()).isNotSameAs(owner);
                    assertThat(Thread.currentThread().getName()).isEqualTo("core-account-lane-" + lane);
                    entered.countDown();
                    try {
                        if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                            throw new AssertionError("control task was not released");
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(failure);
                    }
                    return lane + 10;
                });
                assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThat(runtime.pollControlLanes()).isFalse();
                assertThatThrownBy(() -> runtime.controlLaneResult(0)).isInstanceOf(IllegalStateException.class);
                assertThatThrownBy(() -> runtime.dispatchControlLanes(1, lane -> lane))
                        .isInstanceOf(IllegalStateException.class);
            } finally {
                release.countDown();
            }
            awaitControl(runtime);
            assertThat(runtime.controlLaneResult(0)).isEqualTo(10);
            assertThat(runtime.controlLaneResult(1)).isEqualTo(11);
            runtime.dispatchControlLanes(1, lane -> lane + 20);
            awaitControl(runtime);
            assertThat(runtime.controlLaneResult(0)).isEqualTo(20);
            assertThatThrownBy(() -> runtime.controlLaneResult(1)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void businessRejectionWaitsForOtherLanesAndDoesNotPoisonTheNextCommand() throws Exception {
        try (var runtime = new TradingRuntimeState(LaneTopology.productionDefault())) {
            runtime.startAccountLanes();
            var entered = new java.util.concurrent.CountDownLatch(2);
            var release = new java.util.concurrent.CountDownLatch(1);
            try {
                runtime.dispatchControlLanes(3, lane -> {
                    entered.countDown();
                    if (lane == 0) throw new com.surprising.aeron.service.exception.CoreStateRejectedException(
                            "TEST_REJECTION", "account command rejected");
                    try {
                        if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                            throw new AssertionError("other Lane was not released");
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(failure);
                    }
                    return lane;
                });
                assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThat(runtime.pollControlLanes()).isFalse();
            } finally {
                release.countDown();
            }
            assertThatThrownBy(() -> awaitControl(runtime))
                    .isInstanceOf(com.surprising.aeron.service.exception.CoreStateRejectedException.class)
                    .hasMessage("account command rejected");
            runtime.assertAccountLanesHealthy();
            runtime.dispatchControlLanes(1, lane -> 42);
            awaitControl(runtime);
            assertThat(runtime.controlLaneResult(0)).isEqualTo(42);
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
            awaitControl(runtime);
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

    private static void awaitControl(TradingRuntimeState runtime) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (!runtime.pollControlLanes()) {
            if (System.nanoTime() >= deadline) throw new AssertionError("control work did not complete");
            Thread.onSpinWait();
        }
    }
}
