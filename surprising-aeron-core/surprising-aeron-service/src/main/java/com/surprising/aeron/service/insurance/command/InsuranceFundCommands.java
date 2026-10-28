package com.surprising.aeron.service.insurance.command;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.protocol.AdjustInsuranceFundCommand;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.treasury.SystemLedgerCommand;
import com.surprising.aeron.service.treasury.TreasuryRuntime;

/**
 * 保险基金调整的财库控制任务，持有金额输入、回滚范围及完成状态；请求解码与提交发布由 Owner 派发入口负责。
 */
public final class InsuranceFundCommands {
    private InsuranceFundCommands() { }
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
                requireLedgerPhaseComplete();
                runtime.setMetadata(runtime.productLine(), nextRevision);
                done = true;
            }
            return true;
        }
    }
}
