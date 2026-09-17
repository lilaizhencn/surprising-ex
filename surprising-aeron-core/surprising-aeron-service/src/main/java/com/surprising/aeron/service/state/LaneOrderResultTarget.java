package com.surprising.aeron.service.state;

/** 命令拥有的结果槽；对应账户 Lane 写入，Owner acquire 完成回执后读取，提交后才能复用。 */
public interface LaneOrderResultTarget {
    int resultCount();
    long resultOrderId(int index);
    long resultOriginalOrderId(int index);
    void resultOrder(int index, OrderRuntime order, String symbol);

    /** Batch PLACE targets may resolve unchanged orders directly from their owning Lane. */
    default boolean captureUnchangedResults() { return false; }

    /** Whether a result slot was already filled by a changed-order capture. */
    default boolean resultPrepared(int index) { return false; }

    /** 最终账户 Lane 已捕获结果后准备响应；在完成回执发布之前调用。 */
    void prepareResponse();

    /** Matcher proof supplied before the Lane captures its immutable order after-image. */
    default void matcherResult(com.surprising.aeron.service.matching.CoreMatchingResult result) { }

    /** Optional response bytes prepared by the Lane; null means the Owner must use its fallback. */
    default byte[] preparedResponse() { return null; }

    /** Direct single-command responses retain terminal after-images; batch responses preserve
     * their historical omission of orders that were already retired from a Lane. */
    default boolean includeTerminalAfterImage() { return false; }

    /** 直接消费本次 Lane 变更中的不可变 OrderRuntime，不查询全局发布表。 */
    static void capture(LaneOrderResultTarget target, TradingRuntimeState.LaneDelta changes,
                        RuntimeIdentityRegistry identities, AccountLaneState lane) {
        for (int i = 0; i < target.resultCount(); i++) {
            long id = target.resultOrderId(i);
            long original = target.resultOriginalOrderId(i);
            if (!changes.orders.containsKey(id) && (original == 0 || !changes.orders.containsKey(original))) continue;
            // Direct single-command targets may retain an immutable terminal after-image. Batch
            // targets keep the existing protocol behavior and omit orders retired by the Lane.
            OrderRuntime order = lane.orders.get(id);
            if (target.includeTerminalAfterImage() && changes.orders.containsKey(id))
                order = changes.orders.get(id);
            if (order == null && original > 0) {
                order = lane.orders.get(original);
                if (target.includeTerminalAfterImage() && changes.orders.containsKey(original))
                    order = changes.orders.get(original);
            }
            target.resultOrder(i, order, order == null ? null : identities.symbol(order.symbolId()));
        }
        if (target.captureUnchangedResults()) captureUnchanged(target, identities, lane);
        target.prepareResponse();
    }

    /**
     * Batch PLACE result slots also contain accepted orders with no fill. Resolve those slots
     * from the Lane-owned map after all settlement mutations, so the Owner does not perform a
     * second global publication lookup while encoding the response.
     */
    static void captureUnchanged(LaneOrderResultTarget target,
                                 RuntimeIdentityRegistry identities, AccountLaneState lane) {
        for (int index = 0; index < target.resultCount(); index++) {
            if (target.resultPrepared(index)) continue;
            OrderRuntime order = lane.orders.get(target.resultOrderId(index));
            if (order == null) {
                long original = target.resultOriginalOrderId(index);
                if (original > 0) order = lane.orders.get(original);
            }
            target.resultOrder(index, order, order == null ? null : identities.symbol(order.symbolId()));
        }
    }
}
