package com.surprising.aeron.service.state;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class SystemLedgerLaneTest {
    @Test
    void scheduledLedgerOwnershipCannotBeReclaimedBeforeWorkerStartup() throws Exception {
        var ledger = new TreasuryRuntime();
        ledger.setInsurance(3, 100, 0);
        var failure = new AtomicReference<Throwable>();
        Thread successor = new Thread(() -> {
            try {
                ledger.bindOwner();
                ledger.adjustInsurance(3, -10);
            } catch (Throwable problem) { failure.set(problem); }
            finally { ledger.releaseOwnerForHandoff(); }
        });
        ledger.handoffTo(successor);
        assertThatThrownBy(() -> ledger.adjustInsurance(3, -100)).isInstanceOf(IllegalStateException.class);
        successor.start();
        successor.join(3_000);
        assertThat(successor.isAlive()).isFalse();
        assertThat(failure.get()).isNull();
        assertThat(ledger.insurance(3)).isEqualTo(90);
    }

    @Test
    void feesInsuranceAndResidualsHaveOneLaneWriterAndOwnerCannotAccessMoney() throws Exception {
        var ledger = new TreasuryRuntime();
        ledger.setFee(3, 17);
        ledger.setInsurance(3, 100, 0);
        var done = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        var lane = new SystemLedgerLane(ledger, 8, cause -> failure.compareAndSet(null, cause));
        try (lane) {
            assertThatThrownBy(() -> ledger.setFee(3, 999)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> ledger.adjustInsurance(3, -100)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> ledger.fee(3)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(ledger::releaseOwnerForHandoff).isInstanceOf(IllegalStateException.class);
            var contribution = new RuntimeTreasuryDelta();
            contribution.addFee(3, 5);
            contribution.addInsurance(3, -10);
            contribution.addFundingResidual(3, 2);
            contribution.addRoundingResidual(3, -1);
            contribution.addClearing(3, 4);
            assertThat(lane.offer(value -> {
                assertThat(Thread.currentThread().getName()).isEqualTo("core-system-ledger-lane");
                contribution.apply(value);
                assertThat(value.fee(3)).isEqualTo(22);
                assertThat(value.insurance(3)).isEqualTo(90);
                assertThat(value.fundingResidual(3)).isEqualTo(2);
                assertThat(value.roundingResidual(3)).isEqualTo(-1);
                assertThat(value.clearingPnl(3)).isEqualTo(4);
                done.countDown();
            })).isTrue();
            assertThat(done.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(failure.get()).isNull();
        }
        // The original ledger is retained; no replacement or Owner mirror was introduced.
        assertThat(ledger.fee(3)).isEqualTo(22);
        assertThat(ledger.insurance(3)).isEqualTo(90);
    }

    @Test
    void anInFlightAccountFactDoesNotBlockUnrelatedAccountLaneExecution() throws Exception {
        var ledger = new TreasuryRuntime();
        var accountsComplete = new AtomicBoolean();
        var posted = new CountDownLatch(1);
        var accountDone = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        try (var system = new SystemLedgerLane(ledger, 2, cause -> failure.compareAndSet(null, cause));
             var account = new AccountLaneWorker(new AccountLaneState(0, 8),
                     cause -> failure.compareAndSet(null, cause), AccountLaneWorker.WaitStrategy.BLOCKING)) {
            assertThat(system.offer(new SystemLedgerLane.Entry() {
                public boolean ready() { return accountsComplete.get(); }
                public void apply(TreasuryRuntime value) { value.setFee(3, 5); posted.countDown(); }
            })).isTrue();
            account.submit(state -> {
                state.registerUser(7);
                accountsComplete.set(true);
                accountDone.countDown();
            });
            assertThat(accountDone.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(posted.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(failure.get()).isNull();
        }
    }

    @Test
    void aFullLedgerMailboxReportsBackpressureWithoutDroppingMoney() throws Exception {
        var ledger = new TreasuryRuntime();
        var ready = new AtomicBoolean();
        var done = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        try (var lane = new SystemLedgerLane(ledger, 2, cause -> failure.compareAndSet(null, cause))) {
            assertThat(lane.offer(new SystemLedgerLane.Entry() {
                public boolean ready() { return ready.get(); }
                public void apply(TreasuryRuntime value) { value.setFee(3, 5); }
            })).isTrue();
            assertThat(lane.offer(value -> {
                value.setFee(3, Math.addExact(value.fee(3), 7));
                assertThat(value.fee(3)).isEqualTo(12);
                done.countDown();
            })).isTrue();
            assertThat(lane.hasCapacity()).isFalse();
            assertThat(lane.offer(value -> value.setFee(3, 999))).isFalse();
            ready.set(true);
            assertThat(done.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(failure.get()).isNull();
        }
    }
}
