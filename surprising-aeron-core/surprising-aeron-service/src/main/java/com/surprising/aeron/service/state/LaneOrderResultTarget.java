package com.surprising.aeron.service.state;

/** 命令拥有的结果槽；对应账户 Lane 写入，Owner acquire 完成回执后读取，提交后才能复用。 */
public interface LaneOrderResultTarget {
    int resultCount();
    long resultOrderId(int index);
    long resultOriginalOrderId(int index);
    void resultOrder(int index, OrderRuntime order, String symbol);

    /** 直接消费本次 Lane 变更中的不可变 OrderRuntime，不查询全局发布表。 */
    static void capture(LaneOrderResultTarget target, TradingRuntimeState.PublishedLaneChanges changes,
                        RuntimeIdentityRegistry identities, AccountLaneState lane) {
        for (int i = 0; i < target.resultCount(); i++) {
            long id = target.resultOrderId(i);
            long original = target.resultOriginalOrderId(i);
            if (!changes.orders.containsKey(id) && (original == 0 || !changes.orders.containsKey(original))) continue;
            // 终态已按原协议淘汰时返回 null；不能因优化把已删除订单重新暴露到响应中。
            OrderRuntime order = lane.orders.get(id);
            if (order == null && original > 0) order = lane.orders.get(original);
            target.resultOrder(i, order, order == null ? null : identities.symbol(order.symbolId()));
        }
    }
}
