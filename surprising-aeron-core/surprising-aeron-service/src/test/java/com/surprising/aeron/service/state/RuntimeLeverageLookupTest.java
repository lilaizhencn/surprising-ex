package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.service.state.model.CoreLeverageKey;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RuntimeLeverageLookupTest {
    @Test
    void ownerLookupSurvivesAnotherLanesCompleteLookupAndCleanup() throws Exception {
        try (var runtime = new TradingRuntimeState(LaneTopology.productionDefault())) {
            long ownerUser = 8, laneUser = 9;
            runtime.putLeverage(new CoreLeverageKey(ownerUser, "1", CoreMarginMode.CROSS), 2_000_000);
            runtime.putLeverage(new CoreLeverageKey(laneUser, "2", CoreMarginMode.ISOLATED), 3_000_000);
            var ownerEntered = new CountDownLatch(1);
            var laneFinished = new CountDownLatch(1);
            AccountLaneState ownerLane = runtime.accountLanes[runtime.accountLaneId(ownerUser)];
            var original = ownerLane.cold.leverages;
            var blockedLookup = new java.util.concurrent.ConcurrentHashMap<CoreLeverageKey, Long>(original) {
                @Override public Long get(Object key) {
                    ownerEntered.countDown();
                    await(laneFinished);
                    return super.get(key);
                }
            };
            var field = LaneColdState.class.getDeclaredField("leverages");
            field.setAccessible(true);
            field.set(ownerLane.cold, blockedLookup);
            runtime.startAccountLanes();
            try {
                runtime.dispatchControlLanes(1L << runtime.accountLaneId(laneUser), lane -> {
                    await(ownerEntered);
                    try { return runtime.leverage(laneUser, "2", CoreMarginMode.ISOLATED); }
                    finally { laneFinished.countDown(); }
                });
                assertThat(runtime.leverage(ownerUser, "1", CoreMarginMode.CROSS)).isEqualTo(2_000_000);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!runtime.pollControlLanes()) {
                    if (System.nanoTime() >= deadline) throw new AssertionError("Lane lookup did not finish");
                    Thread.onSpinWait();
                }
                assertThat(runtime.controlLaneResult(runtime.accountLaneId(laneUser))).isEqualTo(3_000_000L);
            } finally {
                ownerEntered.countDown();
                laneFinished.countDown();
                field.set(ownerLane.cold, original);
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("lookup handoff timed out");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }
}
