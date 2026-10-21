package com.surprising.aeron.service.state.ledger;

import com.surprising.aeron.protocol.AdjustInsuranceFundCommand;
import com.surprising.aeron.service.exception.CoreStateRejectedException;
import com.surprising.aeron.service.state.TradingRuntimeState;
import com.surprising.aeron.service.state.identity.RuntimeIdentityRegistry;

/** Owns direct runtime treasury insurance-fund adjustments. */
public final class RuntimeInsuranceFundStateTransitions {

    private RuntimeInsuranceFundStateTransitions() {
    }

    public static void adjust(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                       AdjustInsuranceFundCommand command) {
        if (runtime == null || identities == null || command == null) {
            throw new IllegalArgumentException("invalid runtime insurance adjustment");
        }
        runtime.assertOwner();
        if (runtime.systemLedgerStarted()) throw new IllegalStateException("active insurance adjustment requires its asynchronous continuation");
        AdjustmentWork work = prepare(null, runtime, identities, command);
        work.applyLedger(runtime.treasury());
        runtime.incrementCommandRevision();
    }

    /** Owner解析资产和修订号；扣减检查与保险余额修改只在账本内。 */
    public static AdjustmentWork prepare(AdjustmentWork reuse, TradingRuntimeState runtime,
            RuntimeIdentityRegistry identities, AdjustInsuranceFundCommand command) {
        runtime.assertOwner();
        AdjustmentWork work = reuse == null || !reuse.reusable() ? new AdjustmentWork() : reuse;
        work.initializeLedgerCommand(runtime);
        work.runtime = runtime;
        work.assetId = identities.assetId(command.asset());
        work.deltaUnits = command.deltaUnits();
        work.nextRevision = Math.incrementExact(runtime.revision());
        work.dispatched = work.done = false;
        return work;
    }

    public static final class AdjustmentWork extends SystemLedgerCommand implements java.util.function.BooleanSupplier {
        private TradingRuntimeState runtime;
        private int assetId;
        private long deltaUnits, nextRevision;
        private boolean dispatched, done;

        @Override protected void applyLedger(TreasuryRuntime ledger) {
            long current = ledger.insurance(assetId);
            if (deltaUnits < 0 && Math.negateExact(deltaUnits) > current)
                throw new CoreStateRejectedException("INSUFFICIENT_AVAILABLE_BALANCE", "insurance fund balance is insufficient");
            ledger.adjustInsurance(assetId, deltaUnits);
        }

        @Override public boolean getAsBoolean() {
            if (done) return true;
            if (!dispatched) {
                if (!runtime.dispatchLedgerCommand(this)) return false;
                dispatched = true;
            }
            if (!ledgerCommandCompleted()) return false;
            runtime.setMetadata(runtime.productLine(), nextRevision);
            done = true;
            return true;
        }
    }
}
