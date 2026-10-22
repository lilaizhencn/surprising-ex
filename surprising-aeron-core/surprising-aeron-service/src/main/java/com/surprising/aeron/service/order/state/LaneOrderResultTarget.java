package com.surprising.aeron.service.order.state;

import com.surprising.aeron.service.account.AccountLaneState;
import com.surprising.aeron.service.core.change.LaneCommitDelta;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;

/**
 * 原命令拥有的订单结果和响应槽。所属账户 Lane 在结算末尾读取最终业务值并准备响应，Owner 观察完成回执后消费编码字节，提交及消费者退休前不能复用槽。
 */
public interface LaneOrderResultTarget {
    /**
     * 返回本命令需要填充的结果槽数量。
     */
    int resultCount();
    /**
     * 返回指定结果槽主要查询的新单或当前订单编号。
     * @param index 当前命令有效结果槽的零起始下标
     */
    long resultOrderId(int index);
    /**
     * 返回指定槽对应的原订单编号，没有改单或替换原单时可为 0。
     * @param index 当前命令有效结果槽的零起始下标
     */
    long resultOriginalOrderId(int index);
    /**
     * 由所属 Lane 把该槽的最终订单及规范币对文本交给原命令捕获，订单已移除时允许为空。
     * @param index 当前命令有效结果槽的零起始下标
     * @param order 所属 Lane 读到的最终订单或终态后值，没有保留实体时为 null
     * @param instrumentId 对应订单的规范永久币对编号文本，无订单时为 null
     */
    void resultOrder(int index, OrderRuntime order, String instrumentId);

    /**
     * 默认不额外读取无变化结果；批量 PLACE 实现可启用以补齐获准但没有成交变化的订单。
     */
    default boolean captureUnchangedResults() { return false; }

    /**
     * 判断该槽是否已由变化捕获填好，默认 false 让需要补读的实现自行决定。
     * @param index 当前命令有效结果槽的零起始下标
     */
    default boolean resultPrepared(int index) { return false; }

    /**
     * 在最终 Lane 结果捕获后准备原命令响应，必须先于完成回执发布。
     */
    void prepareResponse();

    /**
     * 可选地记录当前批次项 Matcher 接受或拒绝结果，默认不额外存状态，原发布流程在最终释放边界前调用。
     * @param index 当前命令有效结果槽的零起始下标
     * @param accepted 该批次项的 Matcher 是否接受
     */
    default void matcherResult(int index, boolean accepted) { }

    /**
     * 响应准备完成后交出后备字节数组，Owner 按原租约消费并结合逻辑长度读取有效前缀。
     */
    byte[] preparedResponse();

    /**
     * 返回已准备响应的有效字节长度，可能小于固定后备数组容量。
     */
    int preparedResponseLength();

    /**
     * 默认只沿用 Lane 中仍保留的订单；直接单命令实现可启用，以捕获变化缓冲保留的终态后值。
     */
    default boolean includeTerminalAfterImage() { return false; }

    /**
     * 先处理本次变更命中的结果槽，按目标策略选 Lane 当前订单或变化缓冲终态后值，新编号缺失时再取原单；可选补读无变化槽，最后准备响应。
     * @param target 当前命令已经准备好的结果槽接收者
     * @param changes 当前 Lane 在本命令产生的原变更回执，不读取全局镜像
     * @param identities 订单稠密币对到永久编号文本的当前产品映射
     * @param lane 本次结算已经合法持有的账户 Lane
     */
    static void capture(LaneOrderResultTarget target, LaneCommitDelta changes,
                        RuntimeIdentityRegistry identities, AccountLaneState lane) {
        for (/* 本次变化捕获正在填入的结果槽下标。 */ int i = 0; i < target.resultCount(); i++) {
            // 该槽优先读取的新订单或当前订单编号。
            long id = target.resultOrderId(i);
            // 新编号缺失时可读取的原单编号，没有关联原单时为 0。
            long original = target.resultOriginalOrderId(i);
            if (!changes.orders().containsKey(id) && (original == 0 || !changes.orders().containsKey(original))) continue;
            // 直接单命令可保留本次终态后值；批量目标沿用原协议，对 Lane 已退休且未保留的订单仍返回空结果。
            // 按目标策略从当前 Lane 或本命令终态变化中取得的最终订单值。
            OrderRuntime order = lane.order(id);
            if (target.includeTerminalAfterImage() && changes.orders().containsKey(id))
                order = changes.orders().get(id);
            if (order == null && original > 0) {
                order = lane.order(original);
                if (target.includeTerminalAfterImage() && changes.orders().containsKey(original))
                    order = changes.orders().get(original);
            }
            target.resultOrder(i, order, order == null ? null : identities.instrumentId(order.symbolId()));
        }
        if (target.captureUnchangedResults()) captureUnchanged(target, identities, lane);
        target.prepareResponse();
    }

    /**
     * 仅补读尚未准备的结果槽，从所属 Lane 查询新编号或原单，不查询全局发布表；本辅助方法不单独触发响应编码。
     * @param target 当前命令已经准备好的结果槽接收者
     * @param identities 订单稠密币对到永久编号文本的当前产品映射
     * @param lane 本次结算已经合法持有的账户 Lane
     */
    static void captureUnchanged(LaneOrderResultTarget target,
                                 RuntimeIdentityRegistry identities, AccountLaneState lane) {
        for (/* 当前补读或最终刷新阶段的结果槽下标。 */ int index = 0; index < target.resultCount(); index++) {
            if (target.resultPrepared(index)) continue;
            // 按目标策略从当前 Lane 或本命令终态变化中取得的最终订单值。
            OrderRuntime order = lane.order(target.resultOrderId(index));
            if (order == null) {
                // 新编号缺失时可读取的原单编号，没有关联原单时为 0。
                long original = target.resultOriginalOrderId(index);
                if (original > 0) order = lane.order(original);
            }
            target.resultOrder(index, order, order == null ? null : identities.instrumentId(order.symbolId()));
        }
    }

    /**
     * 顺序批次结束后重新读取全部结果槽的 Lane 最终状态，纳入后续项对前面订单的影响，再准备整批响应。
     * @param target 当前命令已经准备好的结果槽接收者
     * @param identities 订单稠密币对到永久编号文本的当前产品映射
     * @param lane 本次结算已经合法持有的账户 Lane
     */
    static void captureFinal(LaneOrderResultTarget target,
                             RuntimeIdentityRegistry identities, AccountLaneState lane) {
        for (/* 当前补读或最终刷新阶段的结果槽下标。 */ int index = 0; index < target.resultCount(); index++) {
            // 按目标策略从当前 Lane 或本命令终态变化中取得的最终订单值。
            OrderRuntime order = lane.order(target.resultOrderId(index));
            // 新编号缺失时可读取的原单编号，没有关联原单时为 0。
            long original = target.resultOriginalOrderId(index);
            if (order == null && original > 0) order = lane.order(original);
            target.resultOrder(index, order, order == null ? null : identities.instrumentId(order.symbolId()));
        }
        target.prepareResponse();
    }
}
