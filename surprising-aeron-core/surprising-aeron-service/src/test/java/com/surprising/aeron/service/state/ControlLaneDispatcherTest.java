package com.surprising.aeron.service.state;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class ControlLaneDispatcherTest {
    @Test
    void accountTasksRunConcurrentlyOnTheirLanesWithoutBlockingOwner() throws Exception {
        var runtime = new TradingRuntimeState(LaneTopology.productionDefault());
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        runtime.startAccountLanes();
        try {
            acquire(runtime);
            runtime.enterAsynchronousCommandScope();
            runtime.dispatchControlLanes(3, lane -> {
                assertThat(Thread.currentThread().getName()).isEqualTo("core-account-lane-" + lane);
                calls.incrementAndGet();
                entered.countDown();
                await(release);
                return lane + 10;
            });
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(runtime.ownerLaneAccess).isFalse();
            assertThat(runtime.pollControlLanes()).isFalse();
            assertThatThrownBy(runtime::requireSnapshotFenceReady).hasMessageContaining("unfinished");
            assertThatThrownBy(() -> runtime.dispatchControlLanes(1, lane -> 0))
                    .isInstanceOf(IllegalStateException.class);
            release.countDown();
            collect(runtime);
            assertThat(runtime.ownerLaneAccess).isFalse();
            assertThat(runtime.controlLaneResult(0)).isEqualTo(10);
            assertThat(runtime.controlLaneResult(1)).isEqualTo(11);
            assertThat(calls).hasValue(2);
            // 不重新接管全部 Lane，也能派发下一个账户片段。
            runtime.dispatchControlLanes(4, lane -> Thread.currentThread().getName());
            collect(runtime);
            assertThat(runtime.controlLaneResult(2)).isEqualTo("core-account-lane-2");
        } finally {
            release.countDown();
            runtime.exitAsynchronousCommandScope();
            runtime.releaseOwnerLaneAccess();
            runtime.close();
        }
    }

    @Test
    void failureWaitsForAllLaneResultsBeforeOwnerCanRollBack() throws Exception {
        var runtime = new TradingRuntimeState(LaneTopology.productionDefault());
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        runtime.startAccountLanes();
        try {
            acquire(runtime);
            runtime.dispatchControlLanes(3, lane -> {
                entered.countDown();
                if (lane == 0) throw new CoreStateRejectedException("INVALID_COMMAND", "rejected on Lane");
                await(release);
                return 7;
            });
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(runtime.pollControlLanes()).isFalse();
            release.countDown();
            assertThatThrownBy(() -> collect(runtime)).isInstanceOf(CoreStateRejectedException.class);
            assertThat(runtime.ownerLaneAccess).isTrue();
            assertThat(runtime.controlLaneResult(1)).isEqualTo(7);
        } finally {
            release.countDown();
            runtime.releaseOwnerLaneAccess();
            runtime.close();
        }
    }

    private static void acquire(TradingRuntimeState runtime) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!runtime.tryAcquireOwnerLaneAccess()) {
            if (System.nanoTime() >= deadline) throw new AssertionError("handoff timed out");
            Thread.onSpinWait();
        }
    }
    private static void collect(TradingRuntimeState runtime) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!runtime.pollControlLanes()) {
            if (System.nanoTime() >= deadline) throw new AssertionError("Lane timed out");
            Thread.onSpinWait();
        }
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("test release timed out"); }
        catch (InterruptedException failure) { throw new AssertionError(failure); }
    }
}
