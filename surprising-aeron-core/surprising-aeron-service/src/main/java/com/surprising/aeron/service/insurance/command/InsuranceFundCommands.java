package com.surprising.aeron.service.insurance.command;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.protocol.AdjustInsuranceFundCommand;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.TradingCoreRuntime;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.treasury.SystemLedgerCommand;
import com.surprising.aeron.service.treasury.TreasuryRuntime;

/**
 * 保险基金调整入口，解码后使用原财库转换或可复用异步控制任务；发布请求与财务任务真正完成分开管理。
 */
public final class InsuranceFundCommands {
    /** 本产品线交易 Owner，提供原财库、身份表及保险调整控制槽。 */
    private final TradingCoreRuntime owner;

    /**
     * 保存非空 Owner 引用，复用其保险调整控制槽。
     * @param owner 唯一交易 Owner 入口，沿用当前命令与提交边界
     */
    public InsuranceFundCommands(TradingCoreRuntime owner) {
        this.owner = java.util.Objects.requireNonNull(owner);
    }

    /**
     * 准备可复用保险基金调整并登记控制续步，请求发布，最终成功等待控制完成。
     * @param message 本次借用的原命令及载荷，调用期间保持缓冲有效
     */
    public void executeAdjustInsuranceFund(CoreMessage message) {
        var command = TradingCommandCodec.decodeAdjustInsuranceFund(message.payloadUnsafe());
        owner.directCommand.deferControl(prepare(
                owner.runtimeState(), owner.identities(), command));
        owner.requestCommitPublication();
    }

    /**
     * Owner 解析资产、金额及下一产品修订，创建本次调整任务并初始化派发及完成标记。
     */
    public static AdjustmentWork prepare(TradingAccountRuntime runtime,
            RuntimeIdentityRegistry identities, AdjustInsuranceFundCommand command) {
        runtime.assertOwner();
        AdjustmentWork work = new AdjustmentWork();
        work.init(runtime, identities.assetId(command.asset()),
                command.deltaUnits(), Math.incrementExact(runtime.revision()));
        return work;
    }

    /**
     * 同一控制命令持有的保险调整输入及异步完成续步，复用父类财务作用域和回滚前值，不增加资金镜像。
     */
    public static final class AdjustmentWork extends SystemLedgerCommand implements java.util.function.BooleanSupplier {
        private int assetId;
        private long deltaUnits, nextRevision;
        private boolean done;

        void init(TradingAccountRuntime runtime, int assetId, long deltaUnits, long nextRevision) {
            initializeLedgerCommand(runtime);
            this.assetId = assetId;
            this.deltaUnits = deltaUnits;
            this.nextRevision = nextRevision;
            this.done = false;
        }

        @Override protected void applyLedger(TreasuryRuntime ledger) {
            long current = ledger.insurance(assetId);
            if (deltaUnits < 0 && Math.negateExact(deltaUnits) > current)
                throw new CoreStateRejectedException("INSUFFICIENT_AVAILABLE_BALANCE", "insurance fund balance is insufficient");
            ledger.adjustInsurance(assetId, deltaUnits);
        }

        @Override public boolean getAsBoolean() {
            if (!done) {
                runtime.dispatchLedgerCommand(this);
                ledgerCommandCompleted();
                runtime.setMetadata(runtime.productLine(), nextRevision);
                done = true;
            }
            return true;
        }
    }
}
