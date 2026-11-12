package com.surprising.aeron.service.treasury;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.service.core.AccountBook;
import com.surprising.aeron.service.core.index.BizIndex;

/** Owner 同步执行控制命令的财库更新。Treasury 前值和持仓索引撤销信息一直保留到提交或回滚结束。 */
public abstract class SystemLedgerCommand {
    private final FundsTotal funds = new FundsTotal();
    private final BizIndex.PositionUndo positionUndo = new BizIndex.PositionUndo();
    protected AccountBook accountBook;
    private boolean scopeClosed = true;
    private RuntimeException rejection;
    private boolean scopeCompleted = true;
    private boolean validated;

    public final boolean reusable() { return scopeClosed; }

    protected final void initializeLedgerCommand(AccountBook accountBook) {
        if (!reusable()) throw new IllegalStateException("Ledger still owns this command scope");
        if (!funds.isEmpty()) throw new IllegalStateException("Ledger funds receipt was not consumed");
        this.accountBook = accountBook;
        rejection = null;
        scopeCompleted = true;
        validated = false;
    }

    protected abstract void applyLedger(SystemFunds ledger);

    protected boolean financialScopeComplete() { return true; }

    /** 同步记账。业务拒绝恢复财库前值，其他异常交外层故障边界。 */
    public final void apply(SystemFunds ledger) {
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

    public final void publishRiskPositionInputs() { accountBook.applyLedgerControlPositions(positionUndo); }

    public final void rollbackScope() {
        accountBook.systemFunds().rollbackChangedValues();
        funds.clear();
        accountBook.rollbackLedgerPositions(positionUndo);
        accountBook.systemFunds().clearChangedKeys();
        scopeClosed = true;
    }

    public final void commitScope() {
        if (!scopeCompleted) throw new IllegalStateException("Ledger financial scope is incomplete");
        positionUndo.clear();
        accountBook.systemFunds().clearChangedKeys();
        scopeClosed = true;
    }

    /** 同步核对资金并应用持仓索引。 */
    public final void validateFunds(boolean externalAdjustment) {
        requireLedgerAccepted();
        if (!scopeCompleted) throw new IllegalStateException("Ledger financial scope is incomplete");
        if (validated) return;
        try {
            accountBook.appendControlAccountFunds(funds);
            funds.requireConserved(externalAdjustment);
            funds.clear();
            accountBook.applyLedgerControlPositions(positionUndo);
            validated = true;
        } catch (Throwable failure) {
            accountBook.recordFailure(failure);
            throw failure;
        }
    }
}
