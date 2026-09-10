package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.service.state.model.AssetBalance;

/** 单个产品账户的异步余额调整；账户 Lane 单写，Owner 只收集结果并提交产品元数据。 */
public final class AccountBalanceAdjustment {
    /** 当前控制命令所属运行时；一个控制窗口只保留一个任务。 */
    private final TradingRuntimeState runtime;
    /** 完整资金共享域的账户身份，与币对分片无关。 */
    private final long userId;
    /** Owner 已解析的资产 ID，Lane 不修改身份注册表。 */
    private final int assetId;
    /** 派发前校验的下一个产品修订号，账户完成后才生效。 */
    private final long nextRevision;
    /** 已完成的任务不能重复提交。 */
    private boolean completed;

    public AccountBalanceAdjustment(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                    long userId, BalanceAdjustmentCommand command) {
        if (runtime == null || identities == null || command == null || userId <= 0)
            throw new IllegalArgumentException("invalid account balance adjustment");
        runtime.assertOwner();
        this.runtime = runtime;
        this.userId = userId;
        assetId = identities.assetId(AssetBalance.normalizeAsset(command.asset()));
        nextRevision = Math.incrementExact(runtime.revision());
        runtime.dispatchControlLanes(runtime.topology().accountLaneMask(userId), lane -> {
            RuntimeCommandProcessor.adjustAccountBalance(runtime, userId, assetId, command.deltaUnits());
            return null;
        });
    }

    public boolean poll() {
        runtime.assertOwner();
        if (completed) return true;
        if (!runtime.pollControlLanes()) return false;
        runtime.markBalanceChanged(userId, assetId);
        runtime.setMetadata(runtime.productLine(), nextRevision);
        completed = true;
        return true;
    }
}
