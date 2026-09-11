package com.surprising.aeron.service.execution;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class OwnerIdleStrategyTest {
    @Test void publicationDuringTheFinalEmptyProbeDoesNotLoseTheWakeup() throws Exception {
        var probing = new CountDownLatch(1);
        var published = new AtomicBoolean();
        var finished = new CountDownLatch(1);
        var available = new AtomicBoolean();
        var failure = new AtomicReference<Throwable>();
        var idle = new OwnerIdleStrategy(() -> {
            boolean observed = available.get();
            probing.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!published.get()) {
                if (System.nanoTime() >= deadline) throw new AssertionError("publication timed out");
                Thread.onSpinWait(); // 此处不能用会消费 unpark permit 的阻塞同步器。
            }
            return observed;
        });
        var parkPeriod = OwnerIdleStrategy.class.getDeclaredField("parkNanos");
        parkPeriod.setAccessible(true);
        parkPeriod.setLong(idle, TimeUnit.SECONDS.toNanos(2));
        Thread owner = Thread.ofPlatform().start(() -> {
            try {
                idle.bindOwner();
                for (int i = 0; i < 110; i++) idle.idle(0, false);
                idle.idle(0, false);
                // 不能依赖生产环境的100us定时唤醒掩盖丢通知；本次park人为拉长到2秒。
                assertThat(available.get()).isTrue();
                idle.idle(1, false);
            } catch (Throwable e) { failure.set(e); }
            finally { finished.countDown(); }
        });
        try {
            assertThat(probing.await(5, TimeUnit.SECONDS)).isTrue();
            available.set(true);
            idle.signal();
        } finally { published.set(true); }
        try { assertThat(finished.await(1, TimeUnit.SECONDS)).isTrue(); }
        finally { owner.interrupt(); owner.join(5_000); }
        assertThat(failure.get()).isNull();
    }

    @Test void realProgressResetsBackoffWithoutProbingOrParking() {
        var probes = new java.util.concurrent.atomic.AtomicInteger();
        var idle = new OwnerIdleStrategy(() -> { probes.incrementAndGet(); return true; });
        idle.bindOwner();
        for (int round = 0; round < 20; round++) {
            for (int i = 0; i < 110; i++) idle.idle(0, false);
            idle.idle(1, false);
        }
        assertThat(probes.get()).isZero();
        for (int i = 0; i < 111; i++) idle.idle(0, false);
        assertThat(probes.get()).isOne();
    }

    @Test void pendingCommandsResetBackoffWithoutClaimingCompletedWork() {
        var probes = new java.util.concurrent.atomic.AtomicInteger();
        var idle = new OwnerIdleStrategy(() -> { probes.incrementAndGet(); return true; });
        idle.bindOwner();
        for (int round = 0; round < 20; round++) {
            for (int i = 0; i < 110; i++) idle.idle(0, false);
            idle.idle(0, true);
        }
        assertThat(probes.get()).isZero();
        for (int i = 0; i < 111; i++) idle.idle(0, false);
        assertThat(probes.get()).isOne();
    }
}
