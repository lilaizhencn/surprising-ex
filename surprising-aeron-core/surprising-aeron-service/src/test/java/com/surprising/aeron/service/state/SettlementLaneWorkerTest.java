package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SettlementLaneWorkerTest {
    @org.junit.jupiter.api.Test
    void controlOwnershipHandoffIsPolledAndQueuedWorkResumesOnlyAfterRelease() throws Exception {
        var lane = new AccountLaneState(0, 8);
        try (var worker = new SettlementLaneWorker("handoff", lane, 8)) {
            for (long epoch = 1; epoch <= 8; epoch++) {
                var entered = new CountDownLatch(1);
                var release = new CountDownLatch(1);
                var resumed = new CountDownLatch(1);
                worker.submit(value -> {
                    entered.countDown();
                    try { if (!release.await(2, TimeUnit.SECONDS)) throw new AssertionError("release timeout"); }
                    catch (InterruptedException e) { throw new AssertionError(e); }
                });
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                worker.requestHandoff(epoch);
                try { assertThat(worker.handoffReady(epoch)).isFalse(); }
                finally { release.countDown(); }
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (!worker.handoffReady(epoch) && System.nanoTime() < deadline) Thread.yield();
                assertThat(worker.handoffReady(epoch)).isTrue();
                lane.bindOwner();
                worker.submit(value -> { value.assertOwner(); resumed.countDown(); });
                lane.assertOwner();
                assertThat(resumed.getCount()).isOne();
                lane.releaseOwnerForHandoff();
                worker.resumeHandoff(epoch);
                assertThat(resumed.await(2, TimeUnit.SECONDS)).isTrue();
                worker.assertHealthy();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 256})
    void parkedAndActiveHandoffsKeepOrderAndDrainBeforeClose(int spins) throws Exception {
        String key = "surprising.aeron.settlement-spin-limit";
        String previous = System.getProperty(key);
        System.setProperty(key, Integer.toString(spins));
        try (var worker = new SettlementLaneWorker("test", new AccountLaneState(0, 8), 8)) {
            var field = SettlementLaneWorker.class.getDeclaredField("thread");
            field.setAccessible(true);
            var thread = (Thread) field.get(worker);
            var executed = new AtomicInteger();
            // Repeat actual park -> publication and publication -> active consumer races.
            for (int round = 0; round < 32; round++) {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (LockSupport.getBlocker(thread) != worker && System.nanoTime() < deadline) Thread.yield();
                assertThat(LockSupport.getBlocker(thread)).isSameAs(worker);
                var entered = new CountDownLatch(1);
                var release = new CountDownLatch(1);
                var complete = new CountDownLatch(8);
                int base = round * 9;
                worker.submit(lane -> {
                    entered.countDown();
                    try {
                        if (!release.await(2, TimeUnit.SECONDS)) throw new AssertionError("test release timeout");
                    } catch (InterruptedException e) { throw new AssertionError(e); }
                    if (executed.getAndIncrement() != base) throw new AssertionError("first command reordered");
                });
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                try {
                    for (int item = 1; item <= 8; item++) {
                        int expected = base + item;
                        worker.submit(lane -> {
                            if (executed.getAndIncrement() != expected) throw new AssertionError("command reordered");
                            complete.countDown();
                        });
                    }
                    assertThatThrownBy(() -> worker.submit(lane -> {})).isInstanceOf(RejectedExecutionException.class);
                } finally { release.countDown(); }
                assertThat(complete.await(2, TimeUnit.SECONDS)).isTrue();
                worker.assertHealthy();
            }
            worker.submit(lane -> executed.incrementAndGet());
            worker.close();
            assertThat(executed.get()).isEqualTo(32 * 9 + 1);
            assertThat(thread.isAlive()).isFalse();
        } finally {
            if (previous == null) System.clearProperty(key); else System.setProperty(key, previous);
        }
    }
}
