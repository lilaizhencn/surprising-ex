package com.surprising.aeron.service.settlement.command;

import com.surprising.aeron.service.core.TradingCoreRuntime;
import com.surprising.aeron.service.settlement.lifecycle.RuntimeLifecycleSettlementContinuation;
import java.util.List;

/**
 * 币对到期或固定价格结算的命令接入与结果登记。先选择撤单变化，再按实际续步推进生命周期处理；用户范围使用账本选页回执，避免结果路径重新遍历活动索引。
 */
public final class InstrumentSettlementCommands {
    /** 本产品线交易 Owner，提供原索引、运行时和命令结果构建器。 */
    private final TradingCoreRuntime owner;

    /**
     * 绑定当前产品线交易 Owner，空引用直接拒绝。
     * @param owner 当前产品线交易编排入口
     */
    public InstrumentSettlementCommands(TradingCoreRuntime owner) {
        this.owner = java.util.Objects.requireNonNull(owner);
    }

    /**
     * 读取当前结算进度并按 Matcher 活动订单选择本页路由；撤单尚未完成时把订单及用户加入结果，已完成则清空本命令的订单变化列表。本方法只登记变化范围，不实际执行撤单。
     * @param command 币对结算编号、固定价格及订单和用户分页参数
     */
    public void applySettlementChangedIds(com.surprising.aeron.protocol.SettleInstrumentCommand command) {
        // 当前币对已有的生命周期结算进度，用来判断是否还处于撤单阶段。
        var progress = owner.lifecycleProgress(command.instrumentId());
        if (progress == null || !progress.ordersComplete()) {
            // Matcher 索引按当前订单游标选出的路由页，不表示本方法已撤单。
            TradingCoreRuntime.LifecycleOrderChunk chunk = owner.matchingFlow.lifecycleOrders(
                    0, command.instrumentId(), command.cursorOrderId(), command.maxOrders());
            owner.resultBuilder.commandChangedOrderIds = TradingCoreRuntime.boxedOrderIds(chunk.orders());
            owner.resultBuilder.addChangedUsersFromOrders(chunk.orders());
            return;
        }
        owner.resultBuilder.commandChangedOrderIds = List.of();
    }

    /**
     * 把持仓用户索引、活动订单索引与原运行时交给续步入口，旧对象满足回收条件时复用；此处不复制账户状态，也不另加执行线程。
     * @param reuse 可选的旧命令槽工作对象，由续步入口判断是否可复用
     * @param command 币对结算编号、固定价格及订单和用户分页参数
     */
    public RuntimeLifecycleSettlementContinuation.SettlementWork beginAsyncSettlement(
            RuntimeLifecycleSettlementContinuation.SettlementWork reuse,
            com.surprising.aeron.protocol.SettleInstrumentCommand command) {
        return RuntimeLifecycleSettlementContinuation.prepare(reuse, command,
                owner.positionUserIndex().usersAfter(command.instrumentId(), command.cursorUserId()),
                owner.activeOrderIndex(), owner.runtimeState(), owner.identities());
    }
}
