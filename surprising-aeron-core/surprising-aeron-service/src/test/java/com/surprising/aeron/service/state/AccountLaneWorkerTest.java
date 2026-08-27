package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AccountLaneWorkerTest {

    @Test
    void ownsStateOnOneFixedThreadAndReturnsThroughTheResponseRing() {
        AccountLaneWorker worker = new AccountLaneWorker(new AccountLaneState(0, 4), "test");
        try {
            String first = worker.invoke(state -> {
                state.registerUser(7);
                return Thread.currentThread().getName();
            });
            String second = worker.invoke(state -> {
                assertThat(state.owns(7)).isTrue();
                return Thread.currentThread().getName();
            });

            assertThat(first).isEqualTo(second).isEqualTo(worker.ownerThreadName());
            assertThat(worker.queueDepth()).isZero();
            assertThat(worker.highWaterMark()).isEqualTo(1);
        } finally {
            worker.close();
        }
    }

    @Test
    void independentLaneOwnersCanExecuteAtTheSameTime() throws InterruptedException {
        AccountLaneWorker first = new AccountLaneWorker(new AccountLaneState(0, 4), "test");
        AccountLaneWorker second = new AccountLaneWorker(new AccountLaneState(1, 4), "test");
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        try {
            AccountLaneWorker.Ticket<String> firstTicket = first.submit(state -> await(entered, release));
            AccountLaneWorker.Ticket<String> secondTicket = second.submit(state -> await(entered, release));

            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            assertThat(first.await(firstTicket)).isNotEqualTo(second.await(secondTicket));
        } finally {
            release.countDown();
            first.close();
            second.close();
        }
    }

    @Test
    void rejectsASecondSequencerWriter() throws InterruptedException {
        AccountLaneWorker worker = new AccountLaneWorker(new AccountLaneState(0, 4), "test");
        try {
            worker.invoke(AccountLaneState::userCount);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread other = new Thread(() -> {
                try {
                    worker.invoke(AccountLaneState::userCount);
                } catch (Throwable throwable) {
                    failure.set(throwable);
                }
            });
            other.start();
            other.join();

            assertThat(failure.get()).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("multiple sequencer writers");
        } finally {
            worker.close();
        }
    }

    @Test
    void rejectsCloseFromOutsideTheBoundSequencerThread() throws InterruptedException {
        AccountLaneWorker worker = new AccountLaneWorker(new AccountLaneState(0, 4), "test");
        try {
            worker.invoke(AccountLaneState::userCount);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread other = new Thread(() -> {
                try {
                    worker.close();
                } catch (Throwable throwable) {
                    failure.set(throwable);
                }
            });

            other.start();
            other.join();

            assertThat(failure.get()).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("sequencer");
        } finally {
            worker.close();
        }
    }

    @Test
    void rejectsAtCapacityInsteadOfBlockingTheSequencer() throws InterruptedException {
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread producer = Thread.ofPlatform().daemon(true).unstarted(() -> {
            AccountLaneWorker worker = new AccountLaneWorker(new AccountLaneState(0, 2), "test");
            try {
                AccountLaneWorker.Ticket<Integer> first = worker.submit(AccountLaneState::userCount);
                AccountLaneWorker.Ticket<Integer> second = worker.submit(AccountLaneState::userCount);
                assertThatThrownBy(() -> worker.submit(AccountLaneState::userCount))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("full");
                worker.await(first);
                worker.await(second);
                assertThat(worker.metricsSnapshot().rejectedSubmissions()).isEqualTo(1);
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                try {
                    worker.close();
                } catch (Throwable throwable) {
                    if (failure.get() == null) failure.set(throwable);
                }
                completed.countDown();
            }
        });

        producer.start();

        assertThat(completed.await(2, TimeUnit.SECONDS))
                .as("a full Account Lane queue must fail closed without blocking its Sequencer")
                .isTrue();
        assertThat(failure.get()).isNull();
    }

    @Test
    void allowsLifecycleCloseAfterTheBoundSequencerTerminates() throws InterruptedException {
        AtomicReference<AccountLaneWorker> reference = new AtomicReference<>();
        Thread sequencer = new Thread(() -> {
            AccountLaneWorker worker = new AccountLaneWorker(new AccountLaneState(0, 4), "test");
            worker.invoke(AccountLaneState::userCount);
            reference.set(worker);
        });
        sequencer.start();
        sequencer.join();

        assertThat(sequencer.isAlive()).isFalse();
        reference.get().close();
    }

    @Test
    void operationFailureStillAllowsEveryLaterTicketToBeReclaimed() {
        AccountLaneWorker worker = new AccountLaneWorker(new AccountLaneState(0, 4), "test");
        try {
            AccountLaneWorker.Ticket<Integer> failed = worker.submit(state -> {
                throw new IllegalStateException("injected lane failure");
            });
            AccountLaneWorker.Ticket<Integer> later = worker.submit(AccountLaneState::userCount);

            assertThatThrownBy(() -> worker.await(failed))
                    .isInstanceOf(IllegalStateException.class).hasMessage("injected lane failure");
            assertThat(worker.await(later)).isZero();
            assertThat(worker.queueDepth()).isZero();
        } finally {
            worker.close();
        }
    }

    @Test
    void parkModeUnparksTheSequencerWhenTheLaneCompletes() throws InterruptedException {
        AccountLaneWorker worker = new AccountLaneWorker(new AccountLaneState(0, 4), "test",
                AccountLaneWorker.WaitMode.PARK, 0, 0);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            AccountLaneWorker.Ticket<String> ticket = worker.submit(state -> await(entered, release));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            Thread releaser = Thread.ofPlatform().start(release::countDown);

            assertThat(worker.await(ticket)).isEqualTo(worker.ownerThreadName());
            releaser.join();
        } finally {
            release.countDown();
            worker.close();
        }
    }

    private static String await(CountDownLatch entered, CountDownLatch release) {
        entered.countDown();
        try {
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("lane release timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("lane worker interrupted", exception);
        }
        return Thread.currentThread().getName();
    }
}
