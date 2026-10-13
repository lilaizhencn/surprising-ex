package com.surprising.aeron.service.state;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class SystemLedgerLaneTest {
    @Test
    void originalTreasuryHasOneWriterUntilWorkerShutdown() throws Exception {
        TreasuryRuntime ledger = new TreasuryRuntime();
        ledger.setFee(3, 17);
        ledger.setInsurance(3, 100, 0);
        ledger.clearChangedKeys();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (var lane = new SystemLedgerLane(ledger, 256, failure::set)) {
            assertThatThrownBy(() -> ledger.fee(3)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> ledger.adjustInsurance(3, 1)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(ledger::releaseOwnerForHandoff).isInstanceOf(IllegalStateException.class);
            CountDownLatch done = new CountDownLatch(1);
            assertThat(lane.offer(actual -> {
                assertThat(actual).isSameAs(ledger);
                assertThat(Thread.currentThread().getName()).isEqualTo("core-system-ledger-lane");
                actual.setFee(3, Math.addExact(actual.fee(3), 5));
                actual.adjustInsurance(3, -10);
                actual.clearChangedKeys();
                done.countDown();
            })).isTrue();
            assertThat(done.await(3, TimeUnit.SECONDS)).isTrue();
            lane.assertHealthy();
        }
        assertThat(failure).hasValue(null);
        assertThat(ledger.fee(3)).isEqualTo(22);
        assertThat(ledger.insurance(3)).isEqualTo(90);
    }

    @Test
    void queuedMoneyWaitsForItsAccountReceiptAndKeepsLogOrder() throws Exception {
        TreasuryRuntime ledger = new TreasuryRuntime();
        AtomicBoolean ready = new AtomicBoolean();
        CountDownLatch firstRead = new CountDownLatch(1), done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (var lane = new SystemLedgerLane(ledger, 256, failure::set)) {
            assertThat(lane.offer(new SystemLedgerLane.Entry() {
                @Override public boolean ready() { firstRead.countDown(); return ready.get(); }
                @Override public void apply(TreasuryRuntime actual) { actual.setInsurance(1, 30, 0); }
            })).isTrue();
            assertThat(lane.offer(actual -> {
                assertThat(actual.insurance(1)).isEqualTo(30);
                actual.adjustInsurance(1, -20);
                actual.clearChangedKeys();
                done.countDown();
            })).isTrue();
            try {
                assertThat(firstRead.await(3, TimeUnit.SECONDS)).isTrue();
                assertThat(done.getCount()).isEqualTo(1);
                // Ledger readiness does not acquire or park unrelated Account Lane threads.
                CountDownLatch accountDone = new CountDownLatch(1);
                Thread account = new Thread(accountDone::countDown, "independent-account-lane");
                account.start();
                assertThat(accountDone.await(3, TimeUnit.SECONDS)).isTrue();
                account.join(3_000L);
            } finally { ready.set(true); lane.signalWork(); }
            assertThat(done.await(3, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(failure).hasValue(null);
        assertThat(ledger.insurance(1)).isEqualTo(10);
    }

    @Test
    void fullMailboxDoesNotAcceptAnUnownedSlotAndRejectsForeignProducers() throws Exception {
        TreasuryRuntime ledger = new TreasuryRuntime();
        AtomicBoolean ready = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (var lane = new SystemLedgerLane(ledger, 2, failure::set)) {
            SystemLedgerLane.Entry held = new SystemLedgerLane.Entry() {
                @Override public boolean ready() { return ready.get(); }
                @Override public void apply(TreasuryRuntime actual) { actual.setFee(1, 1); }
            };
            try {
                assertThat(lane.offer(held)).isTrue();
                assertThat(lane.offer(actual -> actual.setFee(1, 2))).isTrue();
                assertThat(lane.offer(actual -> actual.setFee(1, 3))).isFalse();
                AtomicReference<Throwable> foreign = new AtomicReference<>();
                Thread producer = new Thread(() -> {
                    try { lane.offer(held); } catch (Throwable problem) { foreign.set(problem); }
                });
                producer.start(); producer.join(3_000L);
                assertThat(producer.isAlive()).isFalse();
                assertThat(foreign.get()).isInstanceOf(IllegalStateException.class);
            } finally { ready.set(true); lane.signalWork(); }
        }
        assertThat(failure).hasValue(null);
        assertThat(ledger.fee(1)).isEqualTo(2);
    }

    @Test
    void repeatedSlotsPublishActualFundsBeforeCompletionAndRetainCapacity() {
        TreasuryRuntime ledger = new TreasuryRuntime();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        TreasurySlot[] slots = new TreasurySlot[256];
        for (int index = 0; index < slots.length; index++) slots[index] = new TreasurySlot();
        try (var lane = new SystemLedgerLane(ledger, 256, failure::set)) {
            for (int round = 1; round <= 128; round++) {
                for (TreasurySlot slot : slots) {
                    slot.completed = false;
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                    while (!lane.offer(slot)) {
                        if (System.nanoTime() >= deadline) throw new AssertionError("mailbox offer timeout");
                        Thread.onSpinWait();
                    }
                }
                for (TreasurySlot slot : slots) {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                    while (!slot.completed) {
                        lane.assertHealthy();
                        if (System.nanoTime() >= deadline) throw new AssertionError("ledger completion timeout");
                        Thread.onSpinWait();
                    }
                    assertThat(slot.fee).isPositive();
                    assertThat(slot.insurance).isEqualTo(slot.fee * 2);
                }
            }
        }
        assertThat(failure).hasValue(null);
        assertThat(ledger.fee(1)).isEqualTo(32_768);
        assertThat(ledger.insurance(1)).isEqualTo(65_536);
    }

    @Test
    void overflowIsFatalAndDoesNotPublishCompletionOrPartiallyWriteFunds() throws Exception {
        TreasuryRuntime ledger = new TreasuryRuntime();
        ledger.setInsurance(1, Long.MAX_VALUE, 0);
        ledger.clearChangedKeys();
        RuntimeTreasuryDelta delta = new RuntimeTreasuryDelta();
        delta.addFee(1, 7); delta.addInsurance(1, 1);
        AtomicBoolean completed = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch failed = new CountDownLatch(1);
        var lane = new SystemLedgerLane(ledger, 256, problem -> { failure.set(problem); failed.countDown(); });
        assertThat(lane.offer(actual -> { delta.apply(actual); completed.set(true); })).isTrue();
        assertThat(failed.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(completed).isFalse();
        assertThatThrownBy(lane::assertHealthy).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(lane::close).isInstanceOf(ArithmeticException.class);
        assertThat(failure.get()).isInstanceOf(ArithmeticException.class);
        assertThat(ledger.fee(1)).isZero();
        assertThat(ledger.insurance(1)).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void shutdownRejectsUnreadyFinancialWorkInsteadOfSilentlyDroppingIt() {
        TreasuryRuntime ledger = new TreasuryRuntime();
        AtomicBoolean applied = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var lane = new SystemLedgerLane(ledger, 256, failure::set);
        assertThat(lane.offer(new SystemLedgerLane.Entry() {
            @Override public boolean ready() { return false; }
            @Override public void apply(TreasuryRuntime actual) { applied.set(true); }
        })).isTrue();
        assertThatThrownBy(lane::close).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("before settlement was ready");
        assertThat(applied).isFalse();
        assertThat(failure.get()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void accountReceiptPublishedInsideParkingWindowCannotLoseWakeup() throws Exception {
        TreasuryRuntime ledger = new TreasuryRuntime();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (var lane = new SystemLedgerLane(ledger, 256, failure::set)) {
            for (int iteration = 0; iteration < 512; iteration++) {
                AtomicBoolean ready = new AtomicBoolean();
                CountDownLatch parkedWindow = new CountDownLatch(1), releaseCheck = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(1);
                assertThat(lane.offer(new SystemLedgerLane.Entry() {
                    @Override public boolean ready() {
                        if (!ready.get() && lane.parked()) {
                            parkedWindow.countDown();
                            try {
                                if (!releaseCheck.await(3, TimeUnit.SECONDS))
                                    throw new AssertionError("parking window not released");
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new AssertionError(interrupted);
                            }
                        }
                        return ready.get();
                    }
                    @Override public void apply(TreasuryRuntime actual) {
                        actual.setFee(1, Math.incrementExact(actual.fee(1)));
                    }
                })).isTrue();
                try {
                    assertThat(parkedWindow.await(3, TimeUnit.SECONDS)).isTrue();
                    ready.set(true);
                    lane.signalWork();
                    assertThat(lane.offer(actual -> {
                        actual.setFee(1, Math.incrementExact(actual.fee(1)));
                        actual.clearChangedKeys();
                        done.countDown();
                    })).isTrue();
                } finally { ready.set(true); releaseCheck.countDown(); lane.signalWork(); }
                assertThat(done.await(3, TimeUnit.SECONDS)).isTrue();
            }
        }
        assertThat(failure).hasValue(null);
        assertThat(ledger.fee(1)).isEqualTo(1_024);
    }

    @Test
    void closedMailboxRejectsFurtherMoneyWrites() {
        TreasuryRuntime ledger = new TreasuryRuntime();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var lane = new SystemLedgerLane(ledger, 256, failure::set);
        lane.close();
        assertThatThrownBy(() -> lane.offer(actual -> actual.setFee(1, 1)))
                .isInstanceOf(java.util.concurrent.RejectedExecutionException.class);
        assertThat(failure).hasValue(null);
        assertThat(ledger.fee(1)).isZero();
    }

    @Test void executingHeadReleasesMailboxCapacityBeforeCompletion() throws Exception {
        var treasury = new TreasuryRuntime();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var done = new CountDownLatch(1);
        try (var lane = new SystemLedgerLane(treasury, 1, ignored -> { })) {
            try {
                assertThat(lane.offer(ledger -> {
                    entered.countDown();
                    try { if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("release timeout"); }
                    catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
                    ledger.setFee(0, 1); ledger.clearChangedKeys();
                })).isTrue();
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(lane.depth()).isZero();
                assertThat(lane.offer(ledger -> {
                    assertThat(ledger.fee(0)).isEqualTo(1);
                    ledger.setFee(0, 2); ledger.clearChangedKeys(); done.countDown();
                })).isTrue();
                assertThat(lane.offer(ledger -> { })).isFalse();
            } finally { release.countDown(); }
            assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(treasury.fee(0)).isEqualTo(2);
    }

    private static final class TreasurySlot implements SystemLedgerLane.Entry {
        final RuntimeTreasuryDelta delta = new RuntimeTreasuryDelta();
        long fee, insurance;
        volatile boolean completed;

        TreasurySlot() { delta.addFee(1, 1); delta.addInsurance(1, 2); }

        @Override public void apply(TreasuryRuntime ledger) {
            delta.apply(ledger);
            fee = ledger.fee(1); insurance = ledger.insurance(1);
            ledger.clearChangedKeys();
            completed = true;
        }
    }
}
