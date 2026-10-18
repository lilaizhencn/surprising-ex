package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerArray;
import org.junit.jupiter.api.Test;

class ReadyLaneNotificationTest {
    @Test void concurrentLanesKeepEveryAcknowledgedGenerationIncludingBit63() throws Exception {
        int[] lanes = {0, 1, 2, 7, 15, 31, 47, 63};
        int generations = 10_000;
        var ready = new TradingRuntimeState.PaddedReadyMask(64);
        var acknowledged = new AtomicIntegerArray(64);
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(lanes.length);
        var failed = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        for (int lane : lanes) Thread.ofPlatform().start(() -> {
            try {
                start.await();
                for (int generation = 1; generation <= generations; generation++) {
                    ready.setBit(lane);
                    while (acknowledged.get(lane) < generation) {
                        if (System.nanoTime() - deadline >= 0) throw new AssertionError("lost Lane notification: " + lane);
                        Thread.onSpinWait();
                    }
                }
            } catch (Throwable failure) { failed.compareAndSet(null, failure); }
            finally { done.countDown(); }
        });
        start.countDown();
        while (done.getCount() != 0 && System.nanoTime() - deadline < 0) {
            long mask = ready.getAndClear();
            while (mask != 0) {
                int lane = Long.numberOfTrailingZeros(mask);
                mask &= mask - 1;
                acknowledged.incrementAndGet(lane);
            }
        }
        assertThat(done.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(failed.get()).isNull();
        for (int lane : lanes) assertThat(acknowledged.get(lane)).isEqualTo(generations);
        assertThat(ready.get()).isZero();
    }
}
