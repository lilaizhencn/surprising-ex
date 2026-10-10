package com.surprising.aeron.service.state;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class AccountLaneWorkerTest {
    @Test
    void aScheduledSuccessorOwnsTheStateBeforeItsThreadStarts() throws Exception {
        var lane = new AccountLaneState(0, 8);
        var failure = new AtomicReference<Throwable>();
        Thread successor = new Thread(() -> {
            try {
                lane.bindOwner();
                lane.registerUser(7);
            } catch (Throwable problem) { failure.set(problem); }
            finally { lane.releaseOwnerForHandoff(); }
        });
        lane.handoffTo(successor);
        assertThatThrownBy(lane::bindOwner).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> lane.registerUser(8)).isInstanceOf(IllegalStateException.class);
        successor.start();
        successor.join(3_000);
        assertThat(successor.isAlive()).isFalse();
        assertThat(failure.get()).isNull();
        assertThat(lane.userCount()).isEqualTo(1);
    }

    @Test
    void separateAccountsExecuteConcurrentlyWithExclusiveStateOwnership() throws Exception {
        var first = new AccountLaneState(0, 8);
        var second = new AccountLaneState(1, 8);
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var complete = new CountDownLatch(2);
        var failure = new AtomicReference<Throwable>();
        var firstThread = new AtomicReference<Thread>();
        var secondThread = new AtomicReference<Thread>();
        try (var a = worker(first, failure); var b = worker(second, failure)) {
            a.submit(lane -> {
                firstThread.set(Thread.currentThread());
                assertThatThrownBy(second::userCount).isInstanceOf(IllegalStateException.class);
                entered.countDown();
                await(release);
                lane.registerUser(7);
                assertThat(lane.userCount()).isEqualTo(1);
                complete.countDown();
            });
            b.submit(lane -> {
                secondThread.set(Thread.currentThread());
                assertThatThrownBy(first::userCount).isInstanceOf(IllegalStateException.class);
                entered.countDown();
                await(release);
                lane.registerUser(8);
                complete.countDown();
            });
            try {
                assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
                assertThat(firstThread.get()).isNotSameAs(secondThread.get()).isNotSameAs(Thread.currentThread());
                assertThatThrownBy(first::userCount).isInstanceOf(IllegalStateException.class);
                assertThatThrownBy(first::releaseOwnerForHandoff).isInstanceOf(IllegalStateException.class);
            } finally { release.countDown(); }
            assertThat(complete.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(failure.get()).isNull();
        }
    }

    @Test
    void boundedMailboxPreservesFifoAndDoesNotExecuteAnUnreadyFact() throws Exception {
        var lane = new AccountLaneState(0, 2);
        var ready = new AtomicBoolean();
        var count = new AtomicInteger();
        var complete = new CountDownLatch(2);
        var failure = new AtomicReference<Throwable>();
        try (var worker = worker(lane, failure)) {
            var first = new AccountLaneWorker.Task() {
                public boolean executable() { return ready.get(); }
                public void execute(AccountLaneState state) {
                    assertThat(count.getAndIncrement()).isZero();
                    complete.countDown();
                }
            };
            worker.submit(first);
            worker.submit(state -> {
                assertThat(count.getAndIncrement()).isEqualTo(1);
                complete.countDown();
            });
            assertThat(worker.depth()).isEqualTo(2);
            assertThat(worker.hasCapacity()).isFalse();
            assertThat(worker.offer(state -> fail("full mailbox accepted work"))).isFalse();
            assertThat(count.get()).isZero();
            ready.set(true);
            // No explicit wake-up: a Matcher release publication must also make forward progress.
            assertThat(complete.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(worker.depth()).isZero();
            assertThat(failure.get()).isNull();
        }
    }

    @Test
    void queueSlotsCanWrapWithoutLosingOrDuplicatingCommands() throws Exception {
        var lane = new AccountLaneState(0, 8);
        var count = new AtomicInteger();
        var failure = new AtomicReference<Throwable>();
        var done = new CountDownLatch(1);
        try (var worker = worker(lane, failure)) {
            for (int index = 0; index < 2_000; index++) {
                int expected = index;
                AccountLaneWorker.Task task = state -> assertThat(count.getAndIncrement()).isEqualTo(expected);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (!worker.offer(task)) {
                    assertThat(System.nanoTime()).isLessThan(deadline);
                    Thread.onSpinWait();
                }
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!worker.offer(state -> done.countDown())) {
                assertThat(System.nanoTime()).isLessThan(deadline);
                Thread.onSpinWait();
            }
            assertThat(done.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(count.get()).isEqualTo(2_000);
            assertThat(failure.get()).isNull();
        }
    }

    @Test
    void consumerFailureIsPublishedAndCannotBeAcknowledgedAsSuccess() throws Exception {
        var lane = new AccountLaneState(0, 8);
        var failure = new AtomicReference<Throwable>();
        var failed = new CountDownLatch(1);
        var problem = new IllegalStateException("settlement invariant violated");
        var worker = new AccountLaneWorker(lane, cause -> { failure.set(cause); failed.countDown(); },
                AccountLaneWorker.WaitStrategy.BLOCKING);
        worker.submit(state -> { throw problem; });
        assertThat(failed.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(failure.get()).isSameAs(problem);
        assertThatThrownBy(worker::assertHealthy).isSameAs(problem);
        assertThatThrownBy(() -> worker.offer(state -> {})).isSameAs(problem);
        assertThatThrownBy(worker::close).isSameAs(problem);
    }

    @Test
    void aForeignProducerCannotPublishIntoTheSpscMailbox() throws Exception {
        var lane = new AccountLaneState(0, 8);
        var failure = new AtomicReference<Throwable>();
        try (var worker = worker(lane, failure)) {
            var rejection = new AtomicReference<Throwable>();
            Thread foreign = new Thread(() -> {
                try { worker.offer(state -> {}); }
                catch (Throwable problem) { rejection.set(problem); }
            });
            foreign.start();
            foreign.join(3_000);
            assertThat(foreign.isAlive()).isFalse();
            assertThat(rejection.get()).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("one Owner producer");
            assertThat(worker.depth()).isZero();
            assertThat(failure.get()).isNull();
        }
    }

    @Test
    void shutdownDrainsPublishedReadyWorkAndRejectsFurtherCommands() {
        var lane = new AccountLaneState(0, 8);
        var count = new AtomicInteger();
        var failure = new AtomicReference<Throwable>();
        var worker = worker(lane, failure);
        for (int index = 0; index < 8; index++) worker.submit(state -> count.incrementAndGet());
        worker.close();
        assertThat(count.get()).isEqualTo(8);
        assertThat(failure.get()).isNull();
        assertThatThrownBy(() -> worker.offer(state -> {}))
                .isInstanceOf(java.util.concurrent.RejectedExecutionException.class);
    }

    private static AccountLaneWorker worker(AccountLaneState lane, AtomicReference<Throwable> failure) {
        return new AccountLaneWorker(lane, cause -> failure.compareAndSet(null, cause),
                AccountLaneWorker.WaitStrategy.BLOCKING);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("test Lane release timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
