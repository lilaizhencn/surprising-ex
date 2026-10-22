package com.surprising.aeron.service.order.state;

import com.surprising.aeron.service.core.TradingAccountRuntime;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 订单提交元数据补写入口。原 Lane 负责业务数据，提交阶段在合法范围补时间与日志位置；不保存额外发布状态。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RuntimeOrderCommitStateTransitions {

    /**
     * 检查非负提交时间、日志位置及非空迭代源，再完整遍历一次，使延迟迭代异常在账户任务开始前暴露；这里不额外校验每个编号。
     * @param timestamp 需要补写的非负集群业务毫秒时间
     * @param position 需要补写的非负集群日志位置
     * @param orderIds 本次普通订单编号列表或可重遍历编号源，调用方在同步处理期间保持稳定
     */
    public static void validateStampInputs(long timestamp, long position, Iterable<Long> orderIds) {
        if (timestamp < 0 || position < 0 || orderIds == null)
            throw new IllegalArgumentException("invalid order commit metadata");
        // 在任何账户任务开始前完成输入遍历，保留原准备边界。
        for (Long /* 仅用于完整遍历输入的当前编号，本步骤不读取或修改其订单。 */ ignored : orderIds) { }
    }

    /**
     * 按变更用户派发原账户任务，各 Lane 遍历稳定的命令编号集合，只给存在且元数据不同的订单补提交时间位置；至少一个 Lane 修改过就返回 true。
     * @param runtime 提供原 Lane 派发、订单更新和终态删除入口的所属运行时
     * @param timestamp 需要补写的非负集群业务毫秒时间
     * @param clusterPosition 本次命令确定的集群日志位置
     * @param changedOrderIds 命令独有且可重复遍历的变更订单编号集合，所有 Lane 完成前不能复用
     * @param changedUserIds 用来选择目标账户 Lane 的变更用户编号集合
     */
    public static boolean stampChangedOrdersByLane(
            TradingAccountRuntime runtime, long timestamp, long clusterPosition,
            Iterable<Long> changedOrderIds, Iterable<Long> changedUserIds) {
        if (runtime == null || changedOrderIds == null || changedUserIds == null
                || timestamp < 0 || clusterPosition < 0) {
            throw new IllegalArgumentException("invalid lane-batched runtime order commit metadata");
        }
        runtime.assertOwner();
        if (changedOrderIds instanceof java.util.Collection<?> /* 已经匹配为 Collection 的变更编号源，空集合可立即返回无修改。 */ orders && orders.isEmpty()) return false;
        // 调用方保留本命令编号集合直到同步 Lane 阶段结束；各 Lane 直接重遍历原集合，不再复制第二份装箱列表。
        // 遍历原编号源后是否见到至少一个非空编号，没有候选就不派发账户任务。
        boolean hasCandidates = false;
        for (Long /* 当前候选订单编号，允许空项并在实际补写时跳过。 */ orderId : changedOrderIds) hasCandidates |= orderId != null;
        if (!hasCandidates) return false;
        // 原账户同步阶段按 Lane 返回的是否修改标记，最后只归并布尔结果。
        Object[] results = runtime.executeOwnerSettlements(changedUserIds, /* 原 Lane 阶段回调参数，实际订单读取沿用运行时当前合法账户范围。 */ ignored -> {
            // 当前 Lane 是否至少给一笔订单补写了不同的提交时间或日志位置。
            boolean changed = false;
            for (Long /* 当前候选订单编号，允许空项并在实际补写时跳过。 */ orderId : changedOrderIds) {
                if (orderId == null) continue;
                // 当前 Lane 能读取到的订单，缺失或元数据已经相同就跳过。
                OrderRuntime order = runtime.order(orderId);
                if (order == null || order.updatedAtEpochMillis() == timestamp
                        && order.clusterPosition() == clusterPosition) continue;
                runtime.replaceOrder(order.withCommitMetadata(timestamp, clusterPosition));
                changed = true;
            }
            return changed;
        });
        for (Object /* 当前检查的 Lane 修改结果，任一值为 Boolean.TRUE 就说明发生过补写。 */ result : results) {
            if (Boolean.TRUE.equals(result)) return true;
        }
        return false;
    }

}
