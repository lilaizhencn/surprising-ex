package com.surprising.aeron.service.state.ledger;

import com.surprising.aeron.protocol.AdjustInsuranceFundCommand;
import com.surprising.aeron.protocol.RegisterInstrumentCommand;
import com.surprising.aeron.service.exception.CoreStateRejectedException;
import com.surprising.aeron.service.state.LedgerControlTestSupport;
import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.aeron.service.state.TradingRuntimeState;
import com.surprising.aeron.service.state.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.state.instrument.CoreInstrument;
import com.surprising.aeron.service.state.snapshot.RuntimeStateProjector;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class SystemLedgerControlTest {
    @Test
    void insuranceBudgetAndMoneyAreOnlyReadAndWrittenByOriginalLedger() {
        var identities = new RuntimeIdentityRegistry();
        try (var runtime = RuntimeStateProjector.project(TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL), identities)) {
            int asset = identities.assetId("USDT");
            TreasuryRuntime original = runtime.treasury();
            original.setInsurance(asset, 25, 0); runtime.clearChangedKeys();
            runtime.startSystemLedgerLane();
            assertThatThrownBy(() -> original.insurance(asset)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> original.adjustInsurance(asset, 1)).isInstanceOf(IllegalStateException.class);
            var work = RuntimeInsuranceFundStateTransitions.prepare(null, runtime, identities, new AdjustInsuranceFundCommand("USDT", 50));
            LedgerControlTestSupport.await(runtime, work);
            assertThat(work.getAsBoolean()).isTrue();
            assertThat(work.reusable()).isFalse();
            assertThatThrownBy(() -> runtime.treasuryForQuery(identities)).hasMessageContaining("committed control");
            var funds = new RuntimeFundsAccumulator();
            runtime.appendFundsDelta(funds);
            assertThat(funds.toDelta().postings()).as("Owner consumes completion without money receipts").isEmpty();
            LedgerControlTestSupport.awaitFunds(runtime, true); runtime.clearChangedKeys();
            assertThat(runtime.treasuryForQuery(identities).insuranceBalances()).containsEntry("USDT", 75L);
            assertThat(work.reusable()).isTrue();
            var next = RuntimeInsuranceFundStateTransitions.prepare(work, runtime, identities, new AdjustInsuranceFundCommand("USDT", -20));
            assertThat(next).isSameAs(work);
            LedgerControlTestSupport.await(runtime, next);
            LedgerControlTestSupport.awaitFunds(runtime, true); runtime.clearChangedKeys();
            assertThat(runtime.treasuryForQuery(identities).insuranceBalances()).containsEntry("USDT", 55L);
            assertThat(runtime.revision()).isEqualTo(2);
        }
    }

    @Test
    void fullMailboxLeavesUnacceptedControlSlotReusableAndRetryAppliesMoneyOnce() throws Exception {
        var identities = new RuntimeIdentityRegistry();
        try (var runtime = RuntimeStateProjector.project(TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL), identities)) {
            identities.assetId("USDT"); runtime.startSystemLedgerLane();
            var field = TradingRuntimeState.class.getDeclaredField("systemLedger"); field.setAccessible(true);
            var ledger = (SystemLedgerLane) field.get(runtime);
            var ready = new java.util.concurrent.atomic.AtomicBoolean();
            assertThat(ledger.offer(new SystemLedgerLane.Entry() {
                @Override public boolean ready() { return ready.get(); }
                @Override public void apply(TreasuryRuntime ignored) { }
            })).isTrue();
            var work = RuntimeInsuranceFundStateTransitions.prepare(null, runtime, identities, new AdjustInsuranceFundCommand("USDT", 7));
            try {
                while (ledger.offer(ignored -> { })) { }
                assertThat(runtime.dispatchLedgerCommand(work)).isFalse();
                assertThat(work.reusable()).isTrue();
            } finally { ready.set(true); }
            LedgerControlTestSupport.await(runtime, work);
            LedgerControlTestSupport.awaitFunds(runtime, true); runtime.clearChangedKeys();
            assertThat(runtime.treasuryForQuery(identities).insuranceBalances()).containsEntry("USDT", 7L);
            assertThat(runtime.revision()).isEqualTo(1);
        }
    }

    @Test
    void rejectedInsuranceAndOverflowRestoreBeforeImagesAndLeaveWorkerUsable() {
        var identities = new RuntimeIdentityRegistry();
        try (var runtime = RuntimeStateProjector.project(TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL), identities)) {
            int asset = identities.assetId("USDT");
            runtime.treasury().setInsurance(asset, Long.MAX_VALUE, 0); runtime.clearChangedKeys();
            runtime.startAccountLanes(); runtime.startSystemLedgerLane(); runtime.enterAsynchronousCommandScope();
            try {
                var overflow = RuntimeInsuranceFundStateTransitions.prepare(null, runtime, identities, new AdjustInsuranceFundCommand("USDT", 1));
                assertThatThrownBy(() -> LedgerControlTestSupport.await(runtime, overflow)).isInstanceOf(ArithmeticException.class);
                LedgerControlTestSupport.await(runtime, runtime.beginCommandRollback(0, 1));
                assertThat(runtime.treasuryForQuery(identities).insuranceBalances()).containsEntry("USDT", Long.MAX_VALUE);
                var spend = RuntimeInsuranceFundStateTransitions.prepare(overflow, runtime, identities, new AdjustInsuranceFundCommand("USDT", -Long.MAX_VALUE));
                LedgerControlTestSupport.await(runtime, spend);
                LedgerControlTestSupport.awaitFunds(runtime, true); runtime.clearChangedKeys();
                assertThat(runtime.treasuryForQuery(identities).insuranceBalances()).isEmpty();
                var rejected = RuntimeInsuranceFundStateTransitions.prepare(spend, runtime, identities, new AdjustInsuranceFundCommand("USDT", -1));
                assertThatThrownBy(() -> LedgerControlTestSupport.await(runtime, rejected)).isInstanceOf(CoreStateRejectedException.class);
                LedgerControlTestSupport.await(runtime, runtime.beginCommandRollback(1, 2));
                assertThat(runtime.revision()).isEqualTo(1);
                var credit = RuntimeInsuranceFundStateTransitions.prepare(rejected, runtime, identities, new AdjustInsuranceFundCommand("USDT", 7));
                LedgerControlTestSupport.await(runtime, credit);
                LedgerControlTestSupport.awaitFunds(runtime, true); runtime.clearChangedKeys();
                assertThat(runtime.treasuryForQuery(identities).insuranceBalances()).containsEntry("USDT", 7L);
                runtime.assertAccountLanesHealthy();
            } finally { runtime.exitAsynchronousCommandScope(); }
        }
    }

    @Test
    void failedAccountPhaseRestoresInsuranceReservedByAnIncompleteLedgerScope() {
        var identities = new RuntimeIdentityRegistry();
        try (var runtime = RuntimeStateProjector.project(TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL), identities)) {
            int asset = identities.assetId("USDT");
            runtime.treasury().setInsurance(asset, 30, 0); runtime.clearChangedKeys();
            runtime.startAccountLanes(); runtime.startSystemLedgerLane(); runtime.enterAsynchronousCommandScope();
            try {
                final class InsuranceReservation extends SystemLedgerCommand {
                    InsuranceReservation() { initializeLedgerCommand(runtime); }
                    @Override protected void applyLedger(TreasuryRuntime ledger) { ledger.adjustInsurance(asset, -20); }
                    @Override protected boolean financialScopeComplete() { return false; }
                    boolean poll() { return ledgerCommandCompleted(); }
                }
                var reservation = new InsuranceReservation();
                assertThat(runtime.dispatchLedgerCommand(reservation)).isTrue();
                LedgerControlTestSupport.await(runtime, reservation::poll);
                runtime.dispatchControlLanes(1, ignored -> { throw new CoreStateRejectedException("ACCOUNT_REJECTED", "account settlement rejected"); });
                assertThatThrownBy(() -> LedgerControlTestSupport.await(runtime, runtime::pollControlLanes)).isInstanceOf(CoreStateRejectedException.class);
                LedgerControlTestSupport.await(runtime, runtime.beginCommandRollback(0, 1));
                assertThat(runtime.treasuryForQuery(identities).insuranceBalances()).containsEntry("USDT", 30L);
                assertThat(reservation.reusable()).isTrue();
                runtime.assertAccountLanesHealthy();
            } finally { runtime.exitAsynchronousCommandScope(); }
        }
    }

    @Test
    void ownerAbortAfterFinancialCompletionRestoresMoneyAndProgressOnLedger() {
        var identities = new RuntimeIdentityRegistry();
        try (var runtime = RuntimeStateProjector.project(TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL), identities)) {
            int asset = identities.assetId("USDT"), symbol = identities.symbolId("1");
            TreasuryRuntime original = runtime.treasury();
            var instrument = CoreInstrument.from(ProductLine.LINEAR_PERPETUAL,
                    new RegisterInstrumentCommand("1", ContractType.LINEAR_PERPETUAL.ordinal(), "BTC", "USDT", "USDT", 1, 1, 1, 100_000, 50_000, 0, 0, 0, -1, 0));
            var progress = new TreasuryRuntime.FundingProgressRuntime(3, instrument, 1000, 0, 1, new UUID(1, 1), 100, 1);
            original.setFee(asset, 9); original.setFundingSettlement(symbol, 2); original.setFundingProgress(symbol, progress);
            runtime.clearChangedKeys(); runtime.startAccountLanes(); runtime.startSystemLedgerLane(); runtime.enterAsynchronousCommandScope();
            try {
                final class FinancialWork extends SystemLedgerCommand {
                    FinancialWork() { initializeLedgerCommand(runtime); }
                    @Override protected void applyLedger(TreasuryRuntime ledger) { ledger.setFee(asset, 18); ledger.setFundingSettlement(symbol, 3); }
                    boolean poll() { return ledgerCommandCompleted(); }
                }
                var work = new FinancialWork();
                assertThat(runtime.dispatchLedgerCommand(work)).isTrue();
                LedgerControlTestSupport.await(runtime, work::poll);
                assertThat(original.fundingSettlement(symbol)).isEqualTo(3);
                assertThat(work.reusable()).isFalse();
                runtime.setMetadata(runtime.productLine(), 1);
                var rollback = runtime.beginCommandRollback(0, 1);
                LedgerControlTestSupport.await(runtime, rollback);
                assertThat(rollback.getAsBoolean()).isTrue();
                assertThat(work.reusable()).isTrue();
                assertThat(original.fundingSettlement(symbol)).isEqualTo(2);
                assertThat(original.fundingProgress(symbol)).isSameAs(progress);
                assertThat(runtime.treasuryForQuery(identities).feeBalances()).containsEntry("USDT", 9L);
                assertThat(runtime.revision()).isZero();
            } finally { runtime.exitAsynchronousCommandScope(); }
        }
    }
}
