package com.surprising.aeron.service.treasury;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.index.RuntimeFactIndexes;

/**
 * 控制命令已有续步的账本交接边界。子类保存指令及结果，当前账本所有者执行实际资金更新，Owner 观察阶段完成后决定验证、提交或回滚。Treasury 的首次写入前值一直保留到 ScopeClose 结束，阶段执行完成与整个财务作用域关闭是两个条件。
 */
public abstract class SystemLedgerCommand implements SystemLedgerLane.Entry {
    /** 本控制命令的财库及账户资金核对缓冲，验证或回滚后清空，不是长期余额状态。 */
    private final RuntimeFundsAccumulator funds = new RuntimeFundsAccumulator();
    /** 本命令独占并复用的关闭任务，保留原前值及持仓索引撤销边界。 */
    private final ScopeClose close = new ScopeClose();
    /** 当前代所属运行时，提供账本投递、账户资金和完成通知。 */
    protected TradingAccountRuntime runtime;
    /** 当前账本执行阶段的 volatile 完成位，初始无任务时为 true。 */
    private volatile boolean completed = true;
    /** 账本阶段捕获的业务、算术或参数拒绝，Owner 观察完成后读取。 */
    private RuntimeException rejection;
    /**
     * closeQueued：本轮关闭任务是否已经成功登记到原账本队列。
     * previousScopeOpen：提交当前阶段前是否已有未关闭财务范围，入队未接纳时据此恢复关闭标记。
     */
    private boolean closeQueued, previousScopeOpen;
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
        closeQueued = false;
        rejection = null;
        completed = scopeCompleted = true;
        close.requested = close.rollback = close.validationRequested = close.validated = false;
    }

    /**
     * 阶段未被接纳入队时恢复执行完成位；如果没有沿用上一阶段未关闭作用域，也恢复关闭完成位。
     */
    public final void notAdmitted() {
        completed = true;
        if (!previousScopeOpen) close.completed = true;
    }

    /**
     * 在前一阶段完成且未排队关闭时登记新阶段，记住是否沿用旧财务作用域，并撤下阶段与关闭完成位。
     */
    public final void submitted() {
        if (!completed || closeQueued) throw new IllegalStateException("Ledger command phase was submitted twice");
        rejection = null;
        previousScopeOpen = !close.completed;
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
     * 执行子类记账，财务范围完成才捕获 Treasury 资金增量。业务拒绝、算术和参数异常会恢复财库前值并保存拒绝；其他异常交外层账本故障边界。最后先保存通知目标再发布阶段完成。
     * @param ledger 当前执行方独占的原系统财库
     */
    @Override public final void apply(TreasuryRuntime ledger) {
        try {
            applyLedger(ledger);
            scopeCompleted = financialScopeComplete();
            if (scopeCompleted) ledger.appendFundsDelta(funds);
        } catch (/* 本阶段可作为拒绝处理的业务、算术或参数异常，恢复前值后交给 Owner。 */ CoreStateRejectedException | ArithmeticException | IllegalArgumentException failure) {
            ledger.rollbackChangedValues();
            ledger.clearChangedKeys();
            funds.clear();
            rejection = failure;
            scopeCompleted = true;
        }

        completed = true;
    }

    /**
     * 账本线程尚未激活时，由当前原财库所有者直接执行子类操作并标记完成，不走异步队列及本类拒绝包装。
     * @param ledger 当前执行方独占的原系统财库
     */
    public final void applyBeforeActivation(TreasuryRuntime ledger) {
        applyLedger(ledger);
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
     * 仅当前阶段及财务计算都完成、尚未排队关闭时允许继续风险输入估值，沿用强平批次原业务边界。
     */
    public final boolean allowsRiskInput() { return completed && scopeCompleted && !closeQueued; }

    /**
     * 把本控制范围持仓变化应用到原账本派生索引，同时保留位置撤销信息；调用方遵守既有账本执行所有权。
     */
    public final void publishRiskPositionInputs() { runtime.applyLedgerControlPositions(close.positionUndo); }

    /**
     * 只投递一次当前关闭任务；账本邮箱满时返回 false 并保留原范围供后续重试。
     */
    private boolean queueClose() {
        if (closeQueued) return true;
        if (!runtime.enqueueLedgerScopeClose(close)) return false;
        closeQueued = true;
        return true;
    }

    /**
     * 等待阶段完成并成功登记关闭任务，再请求恢复财库及持仓索引；返回值表示关闭回执已完成，而非仅已请求。
     */
    public final boolean requestRollback() {
        if (!completed || !queueClose()) return false;
        close.rollback = true;
        close.requested = true;
        return close.completed;
    }

    /**
     * 要求阶段和财务计算完成且关闭任务已入队，再允许账本队首真正执行提交关闭。
     */
    public final void commitScope() {
        if (!completed || !scopeCompleted || !closeQueued)
            throw new IllegalStateException("Ledger financial scope is incomplete");
        close.requested = true;
    }

    /**
     * 阶段正常完成后排队关闭任务，发布外部调整开关及资金验证请求；返回已完成验证的标记，此时仍等待 Owner 最终提交或回滚决定。
     * @param externalAdjustment 本命令是否为外部资金调整，true 时按既有规则跳过逐资产合计为零检查
     */
    public final boolean validateFunds(boolean externalAdjustment) {
        if (!ledgerCommandCompleted() || !scopeCompleted) return false;
        if (!queueClose()) return false;
        close.externalAdjustment = externalAdjustment;
        close.validationRequested = true;
        return close.validated;
    }

    /**
     * 占住原账本队首的关闭任务。在 Owner 给出决定前保留同一财务范围前值，可先核对资金与应用持仓索引，之后按提交或回滚决定关闭。
     */
    private final class ScopeClose implements SystemLedgerLane.Entry {
        /** 本控制范围对账本持仓派生索引的撤销暂存，提交清除、回滚恢复。 */
        private final RuntimeFactIndexes.PositionUndo positionUndo = new RuntimeFactIndexes.PositionUndo();
        /** Owner 已决定执行提交或回滚关闭的 volatile 请求位。 */
        private volatile boolean requested;
        /** 关闭任务真正处理完前值及索引后的 volatile 完成位。 */
        private volatile boolean completed = true;
        /** 关闭时是否应恢复财库和派生持仓索引前值，随 requested 发布。 */
        private boolean rollback;
        /** Owner 已发布资金核对输入的 volatile 请求位。 */
        private volatile boolean validationRequested;
        /** 账本完成资金守恒和持仓索引应用的 volatile 回执，仍不等于范围关闭。 */
        private volatile boolean validated;
        /** 本控制命令是否允许外部资金变化，随验证请求发布。 */
        private boolean externalAdjustment;
        /**
         * 账本推进队首时先按请求合并账户资金、检查守恒、应用持仓索引并发布验证完成；只有 Owner 另发最终关闭请求才允许 apply，因此 ready 在此包含验证动作。
         */
        @Override public boolean ready() {
            if (validationRequested && !validated) {
                runtime.appendControlAccountFunds(funds);
                funds.requireConserved(externalAdjustment);
                funds.clear();
                runtime.applyLedgerControlPositions(positionUndo);
                validated = true;
            }
            return requested;
        }
        /**
         * 按决定恢复 Treasury 前值及持仓索引，或清除位置撤销暂存；清理财库变化键后发布关闭完成，通知只用提前保存的运行时。
         * @param ledger 当前执行方独占的原系统财库
         */
        @Override public void apply(TreasuryRuntime ledger) {
            if (rollback) {
                ledger.rollbackChangedValues(); funds.clear();
                runtime.rollbackLedgerPositions(positionUndo);
            } else positionUndo.clear();
            ledger.clearChangedKeys();

            completed = true;
        }
    }
}
