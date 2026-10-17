package com.surprising.aeron.service.state;
import com.surprising.aeron.service.lane.SettlementLaneWorker;
import static org.assertj.core.api.Assertions.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SettlementLaneWorkerTest {
    @ParameterizedTest
    @ValueSource(strings = {"BLOCKING", "BUSY_SPIN", "YIELDING"})
    void pendingLedgerInputKeepsAdmissionFifoWithoutPreventingOlderSettlement(String strategy) throws Exception {
        String key = "surprising.aeron.settlement-wait-strategy", previous = System.getProperty(key);
        System.setProperty(key, strategy);
        var inputReady = new java.util.concurrent.atomic.AtomicBoolean();
        var executed = new AtomicInteger();
        var completed = new CountDownLatch(8);
        var release = new CountDownLatch(1);
        try (var worker = new SettlementLaneWorker("ledger-input", new AccountLaneState(0, 8), 8)) {
            try {
                var entered = new CountDownLatch(1);
                worker.submit(lane -> {
                    entered.countDown();
                    try { if (!release.await(2, TimeUnit.SECONDS)) throw new AssertionError("release timeout"); }
                    catch (InterruptedException failure) { throw new AssertionError(failure); }
                });
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                var olderSettlement = new CountDownLatch(1);
                worker.submit(lane -> olderSettlement.countDown());
                worker.submitAdmission(new SettlementLaneWorker.Command() {
                    @Override public boolean accountReady() { return inputReady.get(); }
                    @Override public void execute(AccountLaneState lane) {
                        if (executed.getAndIncrement() != 0) throw new AssertionError("input admission reordered");
                        completed.countDown();
                    }
                });
                for (int item = 1; item < 8; item++) {
                    int expected = item;
                    worker.submitAdmission(lane -> {
                        if (executed.getAndIncrement() != expected) throw new AssertionError("later admission passed waiting input");
                        completed.countDown();
                    });
                }
                assertThat(worker.hasAdmissionCapacity()).isFalse();
                assertThatThrownBy(() -> worker.submitAdmission(lane -> {})).isInstanceOf(RejectedExecutionException.class);
                release.countDown();
                assertThat(olderSettlement.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(executed.get()).isZero();
                inputReady.set(true);
                assertThat(completed.await(2, TimeUnit.SECONDS)).isTrue();
                worker.assertHealthy();
            } finally { inputReady.set(true); release.countDown(); }
        } finally {
            if (previous == null) System.clearProperty(key); else System.setProperty(key, previous);
        }
    }

    private static java.lang.reflect.Field findField(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldError(name);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 256})
    void admissionPublishedAfterParkingAnnouncementKeepsMakingProgress(int spins) throws Exception {
        String strategyKey = "surprising.aeron.settlement-wait-strategy";
        String spinKey = "surprising.aeron.settlement-spin-limit";
        String previousStrategy = System.getProperty(strategyKey), previousSpin = System.getProperty(spinKey);
        System.setProperty(strategyKey, "BLOCKING");
        System.setProperty(spinKey, Integer.toString(spins));
        try (var worker = new SettlementLaneWorker("admission-wakeup", new AccountLaneState(0, 8), 8)) {
            var parked = findField(SettlementLaneWorker.class, "parked");
            parked.setAccessible(true);
            var executed = new AtomicInteger();
            for (int round = 0; round < 512; round++) {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                boolean announced = false;
                while (!(announced = worker.isParked() || parked.getBoolean(worker)) && System.nanoTime() < deadline) Thread.onSpinWait();
                assertThat(announced).isTrue();
                var completed = new CountDownLatch(1);
                int expected = round;
                worker.submitAdmission(lane -> {
                    if (executed.getAndIncrement() != expected) throw new AssertionError("admission reordered");
                    completed.countDown();
                });
                assertThat(completed.await(2, TimeUnit.SECONDS)).isTrue();
                worker.assertHealthy();
            }
            assertThat(executed.get()).isEqualTo(512);
        } finally {
            if (previousStrategy == null) System.clearProperty(strategyKey); else System.setProperty(strategyKey, previousStrategy);
            if (previousSpin == null) System.clearProperty(spinKey); else System.setProperty(spinKey, previousSpin);
        }
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.Timeout(15)
    void ownerObservesNonNegativeDepthWhileMatcherAndLaneAdvance() throws Exception {
        // Exercise the real ring cursors without invoking settlement on a synthetic payload.
        Class<?> type = Class.forName(SettlementLaneWorker.class.getName() + "$MatcherSettlementRing");
        var constructor = type.getDeclaredConstructor(int.class);
        constructor.setAccessible(true);
        Object ring = constructor.newInstance(8);
        var publish = type.getDeclaredMethod("publish", MatcherSettlementEvent.class);
        var poll = type.getDeclaredMethod("poll");
        var depth = type.getDeclaredMethod("depth");
        publish.setAccessible(true);
        poll.setAccessible(true);
        depth.setAccessible(true);
        var started = new CountDownLatch(1);
        var stop = new java.util.concurrent.atomic.AtomicBoolean();
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var event = new MatcherSettlementEvent();
        Thread advancing = Thread.ofPlatform().start(() -> {
            started.countDown();
            try {
                while (!stop.get()) {
                    publish.invoke(ring, event);
                    poll.invoke(ring);
                }
            } catch (Throwable thrown) { failure.set(thrown); }
        });
        try {
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            for (int sample = 0; sample < 2_000_000; sample++) {
                long observed = (long) depth.invoke(ring);
                if (observed < 0) fail("Owner sampled negative Matcher ring depth: " + observed);
            }
        } finally {
            stop.set(true);
            advancing.join(2_000);
        }
        assertThat(advancing.isAlive()).isFalse();
        assertThat(failure.get()).isNull();
    }

    @org.junit.jupiter.api.Test
    void realLaneFailurePublishesTheOriginalExceptionBeforeOwnerChecks() throws Exception {
        var signal = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var announced = new CountDownLatch(1);
        var failure = new IllegalStateException("lane task failed");
        var worker = new SettlementLaneWorker("failure", new AccountLaneState(0, 8), 8,
                value -> { signal.compareAndSet(null, value); announced.countDown(); });
        try {
            worker.submit(lane -> { throw failure; });
            assertThat(announced.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(signal.get()).isSameAs(failure);
        } finally {
            assertThatThrownBy(worker::close).isSameAs(failure);
        }
    }

    @org.junit.jupiter.api.Test
    void fatalErrorRemainsVisibleWhenItsReportingCallbackAlsoFails() throws Exception {
        var failure = new NoSuchMethodError("synthetic incompatible settlement method");
        var reportingFailure = new IllegalStateException("synthetic reporting failure");
        var reported = new CountDownLatch(1);
        var worker = new SettlementLaneWorker("fatal", new AccountLaneState(0, 8), 8, problem -> {
            reported.countDown();
            throw reportingFailure;
        });
        try {
            worker.submit(lane -> { throw failure; });
            assertThat(reported.await(2, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(worker::assertHealthy).isSameAs(failure);
        } finally {
            assertThatThrownBy(worker::close).isSameAs(failure);
        }
        assertThat(failure.getSuppressed()).containsExactly(reportingFailure);
        assertThat(worker.thread().isAlive()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"BLOCKING", "BUSY_SPIN", "YIELDING"})
    void controlOwnershipHandoffIsPolledAndQueuedWorkResumesOnlyAfterRelease(String strategy) throws Exception {
        String key = "surprising.aeron.settlement-wait-strategy";
        String previous = System.getProperty(key);
        System.setProperty(key, strategy);
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
        } finally {
            if (previous == null) System.clearProperty(key); else System.setProperty(key, previous);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 256})
    void parkedAndActiveHandoffsKeepOrderAndDrainBeforeClose(int spins) throws Exception {
        String key = "surprising.aeron.settlement-spin-limit";
        String previous = System.getProperty(key);
        String strategyKey = "surprising.aeron.settlement-wait-strategy";
        String previousStrategy = System.getProperty(strategyKey);
        System.setProperty(strategyKey, "BLOCKING");
        System.setProperty(key, Integer.toString(spins));
        try (var worker = new SettlementLaneWorker("test", new AccountLaneState(0, 8), 8)) {
            var field = SettlementLaneWorker.class.getDeclaredField("thread");
            field.setAccessible(true);
            var thread = (Thread) field.get(worker);
            var executed = new AtomicInteger();
            // Repeat actual park -> publication and publication -> active consumer races.
            for (int round = 0; round < 32; round++) {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                Object observedBlocker;
                while ((observedBlocker = LockSupport.getBlocker(thread)) != worker
                        && System.nanoTime() < deadline) Thread.yield();
                // Assert the observed park; a permitted/spurious wake-up may follow immediately.
                assertThat(observedBlocker).isSameAs(worker);
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
            if (previousStrategy == null) System.clearProperty(strategyKey); else System.setProperty(strategyKey, previousStrategy);
            if (previous == null) System.clearProperty(key); else System.setProperty(key, previous);
        }
    }
}
