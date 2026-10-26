package com.surprising.aeron.service.treasury;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.index.RuntimeFactIndexes;

/**
 * 控制命令已有续步的账本交接边界。子类保存指令及结果，由当前 Owner 独占执行实际资金更新，
 * 阶段完成后直接执行验证、提交或回滚。Treasury 的首次写入前值一直保留到 ScopeClose 结束。
 */
public abstract class SystemLedgerCommand {
    /** 本控制命令的财库及账户资金核对缓冲，验证或回滚后清空，不是长期余额状态。 */
    private final RuntimeFundsAccumulator funds = new RuntimeFundsAccumulator();
    /** 本命令独占并复用的关闭任务，保留原前值及持仓索引撤销边界。 */
    private final ScopeClose close = new ScopeClose();
    /** 当前代所属运行时，提供账本投递、账户资金和完成通知。 */
    protected TradingAccountRuntime runtime;
    /** 当前执行阶段的完成标记，初始无任务时为 true。 */
    private boolean completed = true;
    /** 账本阶段捕获的业务、算术或参数拒绝，Owner 观察完成后读取。 */
    private RuntimeException rejection;
    /** 子类是否确认财务计算已经结束，允许验证或风险输入继续。 */
    private boolean scopeCompleted = true;

    /**
     * 只有当前执行阶段和关闭回执都完成才允许重新初始化，不把阶段完成当作用域前值已经释放。
     */
    public final boolean reusable() { return completed && close.completed; }

    /**
     * 要求旧代可复用且旧资金收据已消费，再保存所属运行时并初始化本轮拒绝、阶段完成及关闭请求标记；不在这里执行业务记账。
     * @param runtime 本控制命令所属的交易运行时
     */
    protected final void initializeLedgerCommand(TradingAccountRuntime runtime) {
        if (!reusable()) throw new IllegalStateException("Ledger still owns this command scope");
        if (!funds.isEmpty()) throw new IllegalStateException("Ledger funds receipt was not consumed");
        this.runtime = runtime;
        funds.clear();
        rejection = null;
        completed = scopeCompleted = true;
        close.clear();
    }

    /**
     * 在前一阶段完成时登记新阶段，并撤下阶段与关闭完成位。
     */
    public final void submitted() {
        if (!completed) throw new IllegalStateException("Ledger command phase was submitted twice");
        rejection = null;
        completed = false;
        close.completed = false;
    }

    /**
     * 由业务子类在当前账本所有权内执行本阶段资金操作，可继续保留同一命令跨账户阶段的财务作用域。
     * @param ledger 当前执行方独占的原系统财库
     */
    protected abstract void applyLedger(TreasuryRuntime ledger);

    /**
     * 默认本阶段已经结束财务计算；保险预留等业务可覆盖为 false，在后续账户阶段后继续同一作用域。
     */
    protected boolean financialScopeComplete() { return true; }

    /**
     * 执行子类记账，财务范围完成才捕获 Treasury 资金增量。业务拒绝、算术和参数异常会恢复财库前值并保存拒绝；
     * 其他异常交外层故障边界。
     * @param ledger 当前执行方独占的原系统财库
     */
    public final void apply(TreasuryRuntime ledger) {
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

        completed = true;
    }

    /**
     * 阶段未完成返回 false，完成后如保留拒绝异常则重新抛出，否则返回 true。
     */
    protected final boolean ledgerCommandCompleted() {
        if (!completed) return false;
        if (rejection != null) throw rejection;
        return true;
    }

    /**
     * 返回当前账本执行阶段完成位，不表示资金已验证或财务作用域已关闭。
     */
    public final boolean complete() { return completed; }

    /**
     * 仅当前阶段及财务计算都完成时允许继续风险输入估值，沿用强平批次原业务边界。
     */
    public final boolean allowsRiskInput() { return completed && scopeCompleted; }

    /**
     * 把本控制范围持仓变化应用到原账本派生索引，同时保留位置撤销信息；调用方遵守既有账本执行所有权。
     */
    public final void publishRiskPositionInputs() { runtime.applyLedgerControlPositions(close.positionUndo); }

    /**
     * 等待阶段完成，再请求恢复财库及持仓索引；返回值表示关闭回执已完成，而非仅已请求。
     */
    public final boolean requestRollback() {
        if (!completed) return false;
        close.apply(runtime.treasury(), true);
        return close.completed;
    }

    /**
     * 要求阶段和财务计算完成，再执行提交关闭。
     */
    public final void commitScope() {
        if (!completed || !scopeCompleted)
            throw new IllegalStateException("Ledger financial scope is incomplete");
        close.apply(runtime.treasury(), false);
    }

    /**
     * 阶段正常完成后，发布外部调整开关并就地执行资金验证；返回已完成验证的标记。
     * @param externalAdjustment 本命令是否为外部资金调整，true 时按既有规则跳过逐资产合计为零检查
     */
    public final boolean validateFunds(boolean externalAdjustment) {
        if (!ledgerCommandCompleted() || !scopeCompleted) return false;
        try {
            close.validate(externalAdjustment);
            return close.validated;
        } catch (Throwable failure) {
            return false;
        }
    }

    /**
     * 关闭任务。在 Owner 给出决定前保留同一财务范围前值，核对资金与应用持仓索引，之后按提交或回滚决定关闭。
     */
    private final class ScopeClose {
        /** 本控制范围对账本持仓派生索引的撤销暂存，提交清除、回滚恢复。 */
        private final RuntimeFactIndexes.PositionUndo positionUndo = new RuntimeFactIndexes.PositionUndo();
        /** 关闭任务处理完前值及索引后的完成标记。 */
        private boolean completed = true;
        /** 资金守恒和持仓索引应用的验证完成标记。 */
        private boolean validated;

        void clear() {
            validated = false;
        }

        /**
         * 按请求合并账户资金、检查守恒、应用持仓索引并标记验证完成。
         */
        void validate(boolean externalAdjustment) {
            if (!validated) {
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

        /**
         * 按决定恢复 Treasury 前值及持仓索引，或清除位置撤销暂存；清理财库变化键后标记关闭完成。
         * @param ledger 当前执行方独占的原系统财库
         */
        public void apply(TreasuryRuntime ledger, boolean rollback) {
            if (rollback) {
                ledger.rollbackChangedValues();
                funds.clear();
                runtime.rollbackLedgerPositions(positionUndo);
            } else {
                positionUndo.clear();
            }
            ledger.clearChangedKeys();
            completed = true;
        }
    }
}
