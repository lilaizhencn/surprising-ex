package com.surprising.aeron.service.treasury;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.index.RuntimeFactIndexes;

/** Owner 同步执行控制命令的财库更新。Treasury 前值和持仓索引撤销信息一直保留到提交或回滚结束。 */
public abstract class SystemLedgerCommand {
    private final RuntimeFundsAccumulator funds = new RuntimeFundsAccumulator();
    private final RuntimeFactIndexes.PositionUndo positionUndo = new RuntimeFactIndexes.PositionUndo();
    protected TradingAccountRuntime runtime;
    private boolean scopeClosed = true;
    private RuntimeException rejection;
    private boolean scopeCompleted = true;
    private boolean validated;

    public final boolean reusable() { return scopeClosed; }

    protected final void initializeLedgerCommand(TradingAccountRuntime runtime) {
        if (!reusable()) throw new IllegalStateException("Ledger still owns this command scope");
        if (!funds.isEmpty()) throw new IllegalStateException("Ledger funds receipt was not consumed");
        this.runtime = runtime;
        rejection = null;
        scopeCompleted = true;
        validated = false;
    }

    protected abstract void applyLedger(TreasuryRuntime ledger);

    protected boolean financialScopeComplete() { return true; }

    /** 同步记账。业务拒绝恢复财库前值，其他异常交外层故障边界。 */
    public final void apply(TreasuryRuntime ledger) {
        rejection = null;
        scopeClosed = false;
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
    }

    protected final void requireLedgerAccepted() {
        if (rejection != null) throw rejection;
    }

    public final boolean allowsRiskInput() { return scopeCompleted; }

    public final void publishRiskPositionInputs() { runtime.applyLedgerControlPositions(positionUndo); }

    public final void rollbackScope() {
        runtime.treasury().rollbackChangedValues();
        funds.clear();
        runtime.rollbackLedgerPositions(positionUndo);
        runtime.treasury().clearChangedKeys();
        scopeClosed = true;
    }

    public final void commitScope() {
        if (!scopeCompleted) throw new IllegalStateException("Ledger financial scope is incomplete");
        positionUndo.clear();
        runtime.treasury().clearChangedKeys();
        scopeClosed = true;
    }

    /** 同步核对资金并应用持仓索引。 */
    public final void validateFunds(boolean externalAdjustment) {
        requireLedgerAccepted();
        if (!scopeCompleted) throw new IllegalStateException("Ledger financial scope is incomplete");
        if (validated) return;
        try {
            runtime.appendControlAccountFunds(funds);
            funds.requireConserved(externalAdjustment);
            funds.clear();
            runtime.applyLedgerControlPositions(positionUndo);
            validated = true;
        } catch (Throwable failure) {
            runtime.recordFailure(failure);
            throw failure;
        }
    }
}
