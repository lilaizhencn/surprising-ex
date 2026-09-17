package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.service.state.model.AssetBalance;

/** 单个产品账户的异步余额调整；账户 Lane 单写，Owner 只收集结果并提交产品元数据。 */
public final class AccountBalanceAdjustment implements java.util.function.IntFunction<Object> {
    /** 当前控制命令所属运行时；一个控制窗口只保留一个任务。 */
    private TradingRuntimeState runtime;
    /** 完整资金共享域的账户身份，与币对分片无关。 */
    private long userId;
    /** Owner 已解析的资产 ID，Lane 不修改身份注册表。 */
    private int assetId;
    /** 派发前校验的下一个产品修订号，账户完成后才生效。 */
    private long nextRevision;
    private long deltaUnits;
    /** 已完成的任务不能重复提交。 */
    private boolean completed;

    public AccountBalanceAdjustment(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                    long userId, BalanceAdjustmentCommand command) {
        if (runtime == null || identities == null || command == null || userId <= 0)
            throw new IllegalArgumentException("invalid account balance adjustment");
        reset(runtime, identities, userId, command);
    }

    public static AccountBalanceAdjustment prepare(AccountBalanceAdjustment reuse,
            TradingRuntimeState runtime, RuntimeIdentityRegistry identities, long userId,
            BalanceAdjustmentCommand command) {
        if (reuse == null) return new AccountBalanceAdjustment(runtime, identities, userId, command);
        reuse.reset(runtime, identities, userId, command);
        return reuse;
    }

    private void reset(TradingRuntimeState runtime, RuntimeIdentityRegistry identities, long userId,
                       BalanceAdjustmentCommand command) {
        if (runtime == null || identities == null || command == null || userId <= 0)
            throw new IllegalArgumentException("invalid account balance adjustment");
        runtime.assertOwner();
        this.runtime = runtime;
        this.userId = userId;
        this.assetId = identities.assetId(AssetBalance.normalizeAsset(command.asset()));
        this.deltaUnits = command.deltaUnits();
        this.nextRevision = Math.incrementExact(runtime.revision());
        this.completed = false;
        runtime.dispatchControlLanes(runtime.topology().accountLaneMask(userId), this);
    }

    @Override public Object apply(int ignoredLaneId) {
        RuntimeCommandProcessor.adjustAccountBalance(runtime, userId, assetId, deltaUnits);
        return null;
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
