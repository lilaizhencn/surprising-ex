package com.surprising.aeron.service.settlement.command;

import com.surprising.aeron.service.core.TradingCoreRuntime;
import com.surprising.aeron.service.matcher.DeterministicExchangeCoreAdapter.CancellationOrder;
import com.surprising.aeron.service.settlement.lifecycle.RuntimeLifecycleSettlement;
import com.surprising.aeron.service.settlement.lifecycle.RuntimeLifecycleSettlementContinuation;
import java.util.List;
import java.util.UUID;

/**
 * 币对到期或固定价格结算的命令接入与结果登记。先选择撤单变化，再按同步或异步入口推进原生命周期处理；异步用户范围使用账本选页回执，避免结果路径重新遍历活动索引。
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
        // Matcher 索引按当前订单游标选出的路由页，不表示本方法已撤单。
        TradingCoreRuntime.LifecycleOrderChunk chunk = owner.matchingFlow.lifecycleOrders(
                0, command.instrumentId(), command.cursorOrderId(), command.maxOrders());
        if (progress == null || !progress.ordersComplete()) {
            owner.resultBuilder.commandChangedOrderIds = boxedOrderIds(chunk.orders());
            owner.resultBuilder.addChangedUsersFromOrders(chunk.orders());
            return;
        }
        owner.resultBuilder.commandChangedOrderIds = List.of();
    }

    /**
     * 账本未激活时用原语游标把最多 maxUsers 个持仓用户加入结果，不保留装箱用户页；账本已激活时拒绝，在线路径必须使用同一次账本选页回执。
     * @param instrumentId 需要登记变化用户的币对名称
     * @param cursorUserId 全局用户排他游标
     * @param maxUsers 最多登记多少候选用户，非正数只清空本次变化集合
     */
    public void addSettlementUsersToResult(String instrumentId, long cursorUserId, int maxUsers) {
        if (owner.runtimeState().systemLedgerStarted())
            throw new IllegalStateException("active settlement uses its Ledger route receipt");
        owner.resultBuilder.beginChangedUsers();
        if (maxUsers <= 0) return;
        // 严格大于当前游标的下一个持仓用户编号，零表示索引结束。
        long userId = owner.positionUserIndex().higherUserId(instrumentId, cursorUserId);
        // 本次已经登记到结果的候选用户数量。
        int count = 0;
        while (userId != 0 && count < maxUsers) {
            owner.resultBuilder.addChangedUser(userId);
            count++;
            userId = owner.positionUserIndex().higherUserId(instrumentId, userId);
        }
    }

    /**
     * 同步路径先登记候选用户，执行生命周期结算并保存进度；仅当运行时修订号实际改变时请求提交发布。账本激活后的在线调用使用异步入口。
     * @param command 币对结算编号、固定价格及订单和用户分页参数
     * @param commandId 本页结算命令 UUID，异步入口要求非空
     */
    public void settleInstrumentRuntime(com.surprising.aeron.protocol.SettleInstrumentCommand command,
                                        UUID commandId) {
        addSettlementUsersToResult(command.instrumentId(), command.cursorUserId(), command.maxUsers());
        // 同步结算前的运行时修订号，用于判断是否请求发布。
        long beforeRevision = owner.runtimeState().revision();
        owner.resultBuilder.commandSettlementProgress = RuntimeLifecycleSettlement.applyRuntime(command,
                owner.positionUserIndex().usersAfter(command.instrumentId(), command.cursorUserId()), commandId,
                owner.activeOrderIndex(), owner.runtimeState(), owner.identities());
        if (owner.runtimeState().revision() != beforeRevision) owner.requestCommitPublication();
    }

    /**
     * 为本页结算创建新的异步续步工作，实际账户与账本执行由调用方后续轮询。
     * @param command 币对结算编号、固定价格及订单和用户分页参数
     * @param commandId 本页结算命令 UUID，异步入口要求非空
     */
    public RuntimeLifecycleSettlementContinuation.SettlementWork beginAsyncSettlement(
            com.surprising.aeron.protocol.SettleInstrumentCommand command, UUID commandId) {
        return beginAsyncSettlement(null, command, commandId);
    }

    /**
     * 把持仓用户索引、活动订单索引与原运行时交给续步入口，旧对象满足回收条件时复用；此处不复制账户状态，也不另加执行线程。
     * @param reuse 可选的旧命令槽工作对象，由续步入口判断是否可复用
     * @param command 币对结算编号、固定价格及订单和用户分页参数
     * @param commandId 本页结算命令 UUID，异步入口要求非空
     */
    public RuntimeLifecycleSettlementContinuation.SettlementWork beginAsyncSettlement(
            RuntimeLifecycleSettlementContinuation.SettlementWork reuse,
            com.surprising.aeron.protocol.SettleInstrumentCommand command, UUID commandId) {
        return RuntimeLifecycleSettlementContinuation.prepare(reuse, command,
                owner.positionUserIndex().usersAfter(command.instrumentId(), command.cursorUserId()),
                owner.activeOrderIndex(), owner.runtimeState(), owner.identities());
    }

    /**
     * 把撤单路由中的订单编号依次写入新原语数组，并交给只读 Long 列表持有；返回 List<Long> 接口，不为每一项长期保存单独的装箱对象。
     * @param orders 按页顺序排列的撤单路由，空时返回空列表
     */
    private static List<Long> boxedOrderIds(List<CancellationOrder> orders) {
        if (orders == null || orders.isEmpty()) return List.of();
        // 刚分配并交给只读列表接管的订单编号原语数组。
        long[] values = new long[orders.size()];
        for (/* 当前订单路由在列表及编号数组中的共同下标。 */ int index = 0; index < values.length; index++) values[index] = orders.get(index).orderId();
        return com.surprising.aeron.support.collection.ImmutableLongArrayList.takeOwnership(values);
    }
}
