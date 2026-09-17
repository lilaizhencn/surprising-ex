package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.AdjustPositionMarginCommand;

/** 同一账户内的逐仓保证金增减；账户 Lane 修改资金和持仓，Owner 收集已完成的变化。 */
public final class AccountPositionMarginAdjustment implements java.util.function.IntFunction<Object> {
    /** 当前产品控制上下文。 */
    private TradingRuntimeState runtime;
    /** 共享资金账户，不按逐仓持仓另外切分余额。 */
    private long userId;
    /** Owner 已解析的持仓身份。 */
    private long positionKey;
    /** 所属账户分区。 */
    private int laneId;
    /** 账户任务完成后提交的产品修订号。 */
    private long nextRevision;
    private AdjustPositionMarginCommand command;
    /** 防止重复收集与提交。 */
    private boolean completed;

    public AccountPositionMarginAdjustment(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                           long userId, AdjustPositionMarginCommand command) {
        reset(runtime, identities, userId, command);
    }

    public static AccountPositionMarginAdjustment prepare(AccountPositionMarginAdjustment reuse,
            TradingRuntimeState runtime, RuntimeIdentityRegistry identities, long userId,
            AdjustPositionMarginCommand command) {
        if (reuse == null) return new AccountPositionMarginAdjustment(runtime, identities, userId, command);
        reuse.reset(runtime, identities, userId, command);
        return reuse;
    }

    private void reset(TradingRuntimeState runtime, RuntimeIdentityRegistry identities, long userId,
                       AdjustPositionMarginCommand command) {
        this.positionKey = DerivativeAccountCommandProcessor.positionMarginKey(runtime, identities, userId, command);
        this.runtime = runtime;
        this.userId = userId;
        this.command = command;
        this.laneId = runtime.topology().accountLaneId(userId);
        this.nextRevision = Math.incrementExact(runtime.revision());
        this.completed = false;
        runtime.dispatchControlLanes(1L << laneId, this);
    }

    @Override public Object apply(int ignoredLaneId) {
        return DerivativeAccountCommandProcessor.adjustAccountPositionMargin(runtime, userId, positionKey, command);
    }

    public boolean poll() {
        runtime.assertOwner();
        if (completed) return true;
        if (!runtime.pollControlLanes()) return false;
        int assetId = (Integer) runtime.controlLaneResult(laneId);
        runtime.recordUserSettlementChanges(userId, assetId, positionKey);
        runtime.setMetadata(runtime.productLine(), nextRevision);
        completed = true;
        return true;
    }
}
