package com.surprising.aeron.service.core;

import com.surprising.aeron.service.core.index.RuntimeFactIndexes;
import com.surprising.aeron.service.treasury.RuntimeFundsAccumulator;

import java.util.Objects;

/**
 * 没有专用财务命令槽时使用的账本校验续步：
 * 由 Owner 线程就地核对账户资金并保留持仓索引撤销信息，Owner 再选择提交或回滚。
 */
final class ControlFundsValidation {
    private final TradingAccountRuntime runtime;
    /** 执行校验时汇总账户余额差额的复用累加器，校验通过后清空。 */
    private final RuntimeFundsAccumulator funds = new RuntimeFundsAccumulator();
    /** 应用控制持仓索引时保存的撤销信息，回滚则恢复，提交则清除。 */
    private final RuntimeFactIndexes.PositionUndo positionUndo = new RuntimeFactIndexes.PositionUndo();

    private boolean validated;

    ControlFundsValidation(TradingAccountRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime);
    }

    boolean poll(boolean externalAdjustment) {
        if (!validated) {
            runtime.appendControlAccountFunds(funds);
            funds.requireConserved(externalAdjustment);
            funds.clear();
            runtime.applyLedgerControlPositions(positionUndo);
            validated = true;
        }
        return validated;
    }

    boolean requestRollback() {
        if (validated) {
            runtime.rollbackLedgerPositions(positionUndo);
            validated = false;
        }
        return true;
    }

    void clear() {
        if (!validated) return;
        positionUndo.clear();
        validated = false;
    }
}
