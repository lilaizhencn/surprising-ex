package com.surprising.aeron.service.core;

import com.surprising.aeron.service.core.index.RuntimeFactIndexes;
import com.surprising.aeron.service.treasury.RuntimeFundsAccumulator;
import com.surprising.aeron.service.treasury.TreasuryRuntime;

import java.util.Objects;

/**
 * 没有专用财务命令槽时使用的账本校验续步：
 * 由 Owner 线程就地核对账户资金并保留持仓索引撤销信息，Owner 再选择提交或回滚，最后发布完成。
 */
final class ControlFundsValidation implements TreasuryRuntime.Entry {
    private final TradingAccountRuntime runtime;
    /** 执行 ready 时汇总账户余额差额的复用累加器，校验通过后清空。 */
    private final RuntimeFundsAccumulator funds = new RuntimeFundsAccumulator();
    /** 应用控制持仓索引时保存的撤销信息，回滚则恢复，提交则清除。 */
    private final RuntimeFactIndexes.PositionUndo positionUndo = new RuntimeFactIndexes.PositionUndo();

    private boolean queued, externalAdjustment, rollback;
    private volatile boolean validated, requested, completed = true;

    ControlFundsValidation(TradingAccountRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime);
    }

    boolean poll(boolean externalAdjustment) {
        if (queued && requested) {
            if (!completed) return false;
            queued = false;
        }
        if (!queued) {
            this.externalAdjustment = externalAdjustment;
            rollback = requested = validated = false;
            completed = false;
            queued = true;
            ready();
        }
        return validated;
    }

    @Override
    public boolean ready() {
        if (!validated && !requested) {
            runtime.appendControlAccountFunds(funds);
            funds.requireConserved(externalAdjustment);
            funds.clear();
            runtime.applyLedgerControlPositions(positionUndo);
            validated = true;
        }
        return requested;
    }

    @Override
    public void apply(TreasuryRuntime ledger) {
        if (rollback) {
            runtime.rollbackLedgerPositions(positionUndo);
        } else {
            positionUndo.clear();
        }
        completed = true;
    }

    boolean requestRollback() {
        if (!queued) return true;
        rollback = true;
        requested = true;
        apply(runtime.treasury());
        return completed;
    }

    void clear() {
        if (!queued || completed) return;
        if (!validated) throw new IllegalStateException("Ledger funds validation is incomplete");
        requested = true;
        apply(runtime.treasury());
    }
}
