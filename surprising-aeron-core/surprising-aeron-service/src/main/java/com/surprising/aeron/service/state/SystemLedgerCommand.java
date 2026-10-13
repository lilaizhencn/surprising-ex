package com.surprising.aeron.service.state;

import com.surprising.aeron.service.exception.CoreStateRejectedException;

/**
 * 控制命令已有业务续步的账本交接边界。业务子类保留指令和结果，只有账本线程读写Treasury。
 * 财库before-image一直保留到Owner提交或拒绝，资金回执不建立第二份资金状态。
 */
public abstract class SystemLedgerCommand implements SystemLedgerLane.Entry {
    private final RuntimeFundsAccumulator funds = new RuntimeFundsAccumulator();
    private final ScopeClose close = new ScopeClose();
    private TradingRuntimeState runtime;
    private volatile boolean completed = true;
    private RuntimeException rejection;
    private boolean submitted, closeQueued, previousScopeOpen;
    private boolean scopeCompleted = true;

    /** The previous command must have released both execution and its rollback before-image. */
    public final boolean reusable() { return completed && close.completed; }

    protected final void initializeLedgerCommand(TradingRuntimeState runtime) {
        if (!reusable()) throw new IllegalStateException("Ledger still owns this command scope");
        if (!funds.isEmpty()) throw new IllegalStateException("Ledger funds receipt was not consumed");
        this.runtime = runtime;
        funds.clear();
        submitted = closeQueued = false;
        rejection = null;
        completed = scopeCompleted = true;
        close.requested = close.rollback = close.validationRequested = close.validated = false;
    }

    final void notAdmitted() {
        completed = true;
        if (!previousScopeOpen) close.completed = true;
    }

    final void submitted() {
        if (!completed || closeQueued) throw new IllegalStateException("Ledger command phase was submitted twice");
        rejection = null;
        previousScopeOpen = !close.completed;
        completed = false;
        submitted = true;
        close.completed = false;
    }

    protected abstract void applyLedger(TreasuryRuntime ledger);

    /** Insurance reservation may span an Account Lane phase of this same control command. */
    protected boolean financialScopeComplete() { return true; }

    @Override public final void apply(TreasuryRuntime ledger) {
        try {
            applyLedger(ledger);
            scopeCompleted = financialScopeComplete();
            if (scopeCompleted) ledger.appendFundsDelta(funds);
        } catch (CoreStateRejectedException | ArithmeticException | IllegalArgumentException failure) {
            ledger.rollbackChangedValues();
            ledger.clearChangedKeys();
            funds.clear();
            rejection = failure;
            scopeCompleted = true;
        }
        TradingRuntimeState completion = runtime;
        completed = true;
        completion.signalOwnerCompletion();
    }

    /** Before ledger activation the existing caller owns the original Treasury. */
    final void applyBeforeActivation(TreasuryRuntime ledger) {
        applyLedger(ledger);
        completed = true;
    }

    protected final boolean ledgerCommandCompleted() {
        if (!completed) return false;
        if (rejection != null) throw rejection;
        return true;
    }

    final boolean complete() { return completed; }
    final boolean scopeClosed() { return close.completed; }

    private boolean queueClose() {
        if (closeQueued) return true;
        if (!runtime.enqueueLedgerScopeClose(close)) return false;
        closeQueued = true;
        return true;
    }

    final boolean requestRollback() {
        if (!completed || !queueClose()) return false;
        close.rollback = true;
        close.requested = true;
        runtime.signalLedgerWork();
        return close.completed;
    }

    final void commitScope() {
        if (!completed || !scopeCompleted || !closeQueued)
            throw new IllegalStateException("Ledger financial scope is incomplete");
        close.requested = true;
        runtime.signalLedgerWork();
    }

    final boolean validateFunds(boolean externalAdjustment) {
        if (!ledgerCommandCompleted() || !scopeCompleted) return false;
        if (!queueClose()) return false;
        close.externalAdjustment = externalAdjustment;
        close.validationRequested = true;
        runtime.signalLedgerWork();
        return close.validated;
    }

    /** Queue head holds the original before-images until the Owner's commit/rollback decision. */
    private final class ScopeClose implements SystemLedgerLane.Entry {
        private volatile boolean requested;
        private volatile boolean completed = true;
        private boolean rollback;
        private volatile boolean validationRequested;
        private volatile boolean validated;
        private boolean externalAdjustment;
        @Override public boolean ready() {
            if (validationRequested && !validated) {
                runtime.appendControlAccountFunds(funds);
                funds.requireConserved(externalAdjustment);
                funds.clear();
                validated = true;
                runtime.signalOwnerCompletion();
            }
            return requested;
        }
        @Override public void apply(TreasuryRuntime ledger) {
            if (rollback) { ledger.rollbackChangedValues(); funds.clear(); }
            ledger.clearChangedKeys();
            TradingRuntimeState completion = runtime;
            completed = true;
            completion.signalOwnerCompletion();
        }
    }
}
