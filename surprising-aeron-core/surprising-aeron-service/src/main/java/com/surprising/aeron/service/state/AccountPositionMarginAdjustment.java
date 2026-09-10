package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.AdjustPositionMarginCommand;

/** 同一账户内的逐仓保证金增减；账户 Lane 修改资金和持仓，Owner 收集已完成的变化。 */
public final class AccountPositionMarginAdjustment {
    /** 当前产品控制上下文。 */
    private final TradingRuntimeState runtime;
    /** 共享资金账户，不按逐仓持仓另外切分余额。 */
    private final long userId;
    /** Owner 已解析的持仓身份。 */
    private final long positionKey;
    /** 所属账户分区。 */
    private final int laneId;
    /** 账户任务完成后提交的产品修订号。 */
    private final long nextRevision;
    /** 防止重复收集与提交。 */
    private boolean completed;

    public AccountPositionMarginAdjustment(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                           long userId, AdjustPositionMarginCommand command) {
        positionKey = DerivativeAccountCommandProcessor.positionMarginKey(runtime, identities, userId, command);
        this.runtime = runtime;
        this.userId = userId;
        laneId = runtime.topology().accountLaneId(userId);
        nextRevision = Math.incrementExact(runtime.revision());
        runtime.dispatchControlLanes(1L << laneId,
                lane -> DerivativeAccountCommandProcessor.adjustAccountPositionMargin(runtime, userId, positionKey, command));
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
