package com.surprising.aeron.service.treasury;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.protocol.AdjustInsuranceFundCommand;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 保险基金直接增减的业务入口。账本激活前可由原所有者同步处理，激活后准备可复用控制续步，扣款余额检查与保险修改在账本执行，Owner 收到阶段完成后发布产品修订。 构造约定：只提供静态保险调整入口，不允许单独实例化工具类。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RuntimeInsuranceFundStateTransitions {

    /**
     * 仅允许系统账本未激活的原 Owner 同步调整，先检查输入并准备任务，直接应用到原 Treasury 后推进命令修订；账本已激活时要求沿异步续步执行。
     * @param runtime 本次保险调整所属运行时
     * @param identities 解析保险资产编号的运行时字典
     * @param command 包含保险资产和有符号调整金额的复制命令
     */
    public static void adjust(TradingAccountRuntime runtime, RuntimeIdentityRegistry identities,
                       AdjustInsuranceFundCommand command) {
        if (runtime == null || identities == null || command == null) {
            throw new IllegalArgumentException("invalid runtime insurance adjustment");
        }
        runtime.assertOwner();
        if (runtime.systemLedgerStarted()) throw new IllegalStateException("active insurance adjustment requires its asynchronous continuation");
        // 本轮新建并已初始化的保险调整续步。
        AdjustmentWork work = prepare(runtime, identities, command);
        work.applyLedger(runtime.treasury());
        runtime.incrementCommandRevision();
    }

    /**
     * Owner 解析资产、金额及下一产品修订，创建本次调整任务并初始化派发及完成标记。
     * @param runtime 本次保险调整所属运行时
     * @param identities 解析保险资产编号的运行时字典
     * @param command 包含保险资产和有符号调整金额的复制命令
     */
    public static AdjustmentWork prepare(TradingAccountRuntime runtime,
            RuntimeIdentityRegistry identities, AdjustInsuranceFundCommand command) {
        runtime.assertOwner();
        // 本轮新建并已初始化的保险调整续步。
        AdjustmentWork work = new AdjustmentWork();
        work.initializeLedgerCommand(runtime);
        work.assetId = identities.assetId(command.asset());
        work.deltaUnits = command.deltaUnits();
        work.nextRevision = Math.incrementExact(runtime.revision());
        work.dispatched = work.done = false;
        return work;
    }

    /**
     * 同一控制命令持有的保险调整输入及异步完成续步，复用父类财务作用域和回滚前值，不增加资金镜像。
     */
    public static final class AdjustmentWork extends SystemLedgerCommand implements java.util.function.BooleanSupplier {
        /** 本次保险增减的资产稠密编号。 */
        private int assetId;
        /**
         * deltaUnits：本次保险基金有符号调整金额，负数为扣减。
         * nextRevision：Owner 准备阶段预先算好的下一产品修订号。
         */
        private long deltaUnits, nextRevision;
        /**
         * dispatched：本轮任务是否已被原系统账本执行入口接纳。
         * done：Owner 是否已收到账本成功完成并发布修订，防止重复发布。
         */
        private boolean dispatched, done;

        /**
         * 在账本所有权内读取保险余额，扣减超过可用保险时业务拒绝，其余精确调整原保险余额。
         * @param ledger 当前账本执行方独占的原 Treasury
         */
        @Override protected void applyLedger(TreasuryRuntime ledger) {
            // 账本当前该资产的保险基金余额，用于核对负调整是否超额。
            long current = ledger.insurance(assetId);
            if (deltaUnits < 0 && Math.negateExact(deltaUnits) > current)
                throw new CoreStateRejectedException("INSUFFICIENT_AVAILABLE_BALANCE", "insurance fund balance is insufficient");
            ledger.adjustInsurance(assetId, deltaUnits);
        }

        /**
         * Owner 非阻塞投递一次并等待账本阶段回执，成功后发布预先准备的产品修订并记完成；拒绝由父类回执检查抛出，最终范围关闭仍沿父类控制协议。
         */
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
