package com.surprising.aeron.service.state.change;

import lombok.Getter;
import lombok.experimental.Accessors;
import com.surprising.aeron.service.lane.SettlementLaneWorker;
import com.surprising.aeron.service.state.TradingRuntimeState;
import com.surprising.aeron.service.state.account.AccountLaneState;
import com.surprising.aeron.service.state.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.state.order.LaneOrderResultTarget;
import com.surprising.aeron.service.state.order.OrderRuntime;
import com.surprising.aeron.service.state.order.RuntimeTriggerOrderStateTransitions;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import org.eclipse.collections.impl.list.mutable.primitive.LongArrayList;

/**
 * 一个 Core 序号对受影响账户 Lane 的异步提交扇出。各 Lane 完成触发关闭、订单元数据、用户水位及结果捕获后发布自己的完成位，Owner 收齐全部 required 位才发布和回收。requiredLaneMask 是必须回执的分区，committedLaneMask 决定其中哪些分区还要推进用户水位。
 */
public final class LaneCommitEvent implements SettlementLaneWorker.Command {
    /** 完成标记之间间隔十六个 long，避免各 Lane 紧邻写同一小片存储。 */
    private static final int CACHE_LINE_LONGS = 16;
    /** 完成数组元素的 VarHandle，Lane release 写入、Owner acquire 读取。 */
    private static final VarHandle LONGS = MethodHandles.arrayElementVarHandle(long[].class);

    /** 每个 Lane 在本提交边界需要推进的用户编号列表，元数据补写可追加相关用户。 */
    private final LongArrayList[] usersByLane;
    /** 各 Lane 间隔保存的完成标记，零未发布、非零已经发布本事件结果。 */
    private final long[] completedLanes;
    /** 当前事件关联的运行时，Owner 重置后可清空，所以 Lane 发布前先保存通知目标。 */
    private TradingRuntimeState runtime;
    /**  本事件唯一 Core 命令序号，零表示可重新准备。 返回本事件正在处理的 Core 序号，零表示已重置。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long coreSequence;
    /**  必须执行并回执的账户 Lane 位图。 返回必须完成回执的账户 Lane 位集合。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long requiredLaneMask;
    /**  需要在用户应用提交水位上推进的参与 Lane 位图。 返回参与分区中需要推进用户应用提交水位的位集合。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long committedLaneMask;
    /** 可选最终订单响应捕获目标，在指定结果 Lane 上填充。 */
    private LaneOrderResultTarget resultTarget;
    /** 最终响应捕获需要的身份字典，回收事件时解除引用。 */
    private RuntimeIdentityRegistry identities;
    /** 负责捕获最终订单响应的参与 Lane 编号。 */
    private int resultLaneId;

    // 仅需要在最终提交补订单元数据时创建候选列表；各参与 Lane 在自己的表中定位，Owner 不跨账户表查找归属。
    /** 可选的提交元数据候选订单编号列表，参与 Lane 各查自己的权威表。 */
    private LongArrayList metadataOrderIds;
    /** 各 Lane 已独立冻结的元数据订单后值物理缓冲，完成后交 Owner 接管。 */
    private RuntimeOrderChangeBuffer[] metadataOrders;
    /**
     * metadataTimestamp：本次要补入订单的提交时间。
     * metadataPosition：本次要补入订单的 Cluster 日志位置。
     */
    private long metadataTimestamp, metadataPosition;

    /** 按 Lane 分组、需要尝试关闭的待触发记录编号列表。 */
    private LongArrayList[] triggerCancelIds;
    /** 各 Lane 在本事件实际成功关闭的触发数量，完成后由 Owner 收集。 */
    private int[] canceledTriggers;

    /**
     * 在 Owner 路由视图中挑出仍待触发的候选，检查触发版本及目标 Lane 已参与本事件，再按 Lane 追加撤销编号并预检查产品修订容量，不在此写触发权威表。
     * @param ids 准备按当前路由关闭的触发编号候选，可为 null，null 项跳过
     */
    public void prepareTriggerCancellations(Iterable<Long> ids) {
        if (ids == null) return;
        // 当前准备、扩容或汇总的有效候选数量，触发发布时按实际关闭数精确累加。
        int count = 0;
        for (Long /* 当前触发或元数据候选的业务编号。 */ id : ids) {
            if (id == null) continue;
            // Owner 已发布的触发路由信息，用于判断状态与账户归属。
            var trigger = runtime.triggerRoute(id);
            if (trigger == null || trigger.status() != com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING)
                continue;
            if (!trigger.cancelRevisionAvailable()) throw new ArithmeticException("trigger revision overflow");
            // 当前候选或位图对应的实际账户 Lane 编号。
            int laneId = runtime.topology().accountLaneId(trigger.userId());
            if ((requiredLaneMask & (1L << laneId)) == 0)
                throw new IllegalStateException("closing trigger has no account commit Lane");
            if (triggerCancelIds == null) {
                triggerCancelIds = new LongArrayList[usersByLane.length];
                canceledTriggers = new int[usersByLane.length];
            }
            if (triggerCancelIds[laneId] == null) triggerCancelIds[laneId] = new LongArrayList(4);
            triggerCancelIds[laneId].add(id);
            count++;
        }
        Math.addExact(runtime.revision(), count);
    }

    /**
     * 由实际所属 Lane 在命令作用域中逐项取消仍待触发的条件记录，按实际成功数保存本 Lane 关闭计数。
     * @param lane 本次执行回调实际持有的账户 Lane
     */
    private void cancelClosingTriggers(AccountLaneState lane) {
        // 当前候选或位图对应的实际账户 Lane 编号。
        int laneId = lane.laneId();
        if (triggerCancelIds == null || triggerCancelIds[laneId] == null) return;
        runtime.enterLaneCommandScope(lane);
        try {
            // 当前 Lane 的触发列表或共享元数据候选列表，只访问本事件有效项。
            LongArrayList ids = triggerCancelIds[laneId];
            for (/* 当前候选列表中的连续下标。 */ int index = 0; index < ids.size(); index++)
                if (RuntimeTriggerOrderStateTransitions.cancelPendingForCommit(runtime, ids.get(index)))
                    canceledTriggers[laneId]++;
        } finally { runtime.exitLaneCommandScope(lane); }
    }

    /**
     * 非空候选输入要求时间和日志位置非负，追加需要补提交元数据的订单编号；所有参与 Lane 各自准备容量并在执行时查自己的订单表，Owner 不跨 Lane 查表定位。
     * @param orderIds 需要补写提交元数据的候选订单编号，可为 null，null 项跳过
     * @param timestamp 本次提交时间，候选输入非空时要求非负
     * @param position 本次 Cluster 日志位置，候选输入非空时要求非负
     */
    public void prepareMetadata(Iterable<Long> orderIds, long timestamp, long position) {
        if (orderIds == null) return;
        if (timestamp < 0 || position < 0) throw new IllegalArgumentException("invalid commit metadata");
        metadataTimestamp = timestamp;
        metadataPosition = position;
        for (Long /* 输入迭代到的订单编号，null 项跳过。 */ orderId : orderIds) {
            if (orderId == null) continue;
            if (metadataOrderIds == null) {
                metadataOrderIds = new LongArrayList(4);
                metadataOrders = new RuntimeOrderChangeBuffer[usersByLane.length];
            }
            metadataOrderIds.add(orderId);
        }
        if (metadataOrderIds == null) return;
        // 尚未处理的参与 Lane 位图，每次清除最低有效位。
        long lanes = requiredLaneMask;
        while (lanes != 0) {
            // 当前候选或位图对应的实际账户 Lane 编号。
            int laneId = Long.numberOfTrailingZeros(lanes);
            lanes &= lanes - 1;
            // 当前准备、扩容或汇总的有效候选数量，触发发布时按实际关闭数精确累加。
            int count = metadataOrderIds.size();
            if (metadataOrders[laneId] == null) metadataOrders[laneId] = new RuntimeOrderChangeBuffer();
            metadataOrders[laneId].ensureCapacity(count);
        }
    }

    /**
     * 所属 Lane 只处理自己持有且元数据不同的候选订单，保存回滚前值后原位补时间和位置，并把独立订单后值及相关用户登记到本事件。
     * @param lane 本次执行回调实际持有的账户 Lane
     */
    private void stampMetadata(AccountLaneState lane) {
        // 当前候选或位图对应的实际账户 Lane 编号。
        int laneId = lane.laneId();
        if (metadataOrderIds == null) return;
        // 当前 Lane 的触发列表或共享元数据候选列表，只访问本事件有效项。
        LongArrayList ids = metadataOrderIds;
        runtime.enterLaneCommandScope(lane);
        try {
            for (/* 当前候选列表中的连续下标。 */ int index = 0; index < ids.size(); index++) {
                // 当前触发或元数据候选的业务编号。
                long id = ids.get(index);
                // 当前 Lane 在自己的订单表查到的权威记录，缺失可能属于另一参与分区或已退休。
                OrderRuntime order = lane.order(id);
                if (order == null) continue; // 可能由另一参与 Lane 持有，也可能已经退休。
                if (order.updatedAtEpochMillis() == metadataTimestamp && order.clusterPosition() == metadataPosition)
                    continue;
                runtime.accountRollback().captureUserBefore(order.userId());
                runtime.accountRollback().captureOrderMetadataBefore(order);
                order.applyCommitMetadataInPlace(metadataTimestamp, metadataPosition);
                metadataOrders[laneId].put(id, order);
                if (!usersByLane[laneId].contains(order.userId())) usersByLane[laneId].add(order.userId());
            }
        } finally { runtime.exitLaneCommandScope(lane); }
    }

    /**
     * Owner 收齐后发布实际关闭触发的变化与修订，并接管各 Lane 已保存的订单元数据后值和相关用户编号，不重新读取活订单字段。
     */
    private void publishMetadata() {
        if (triggerCancelIds != null) {
            // 当前准备、扩容或汇总的有效候选数量，触发发布时按实际关闭数精确累加。
            int count = 0;
            // 尚未处理的参与 Lane 位图，每次清除最低有效位。
            long lanes = requiredLaneMask;
            while (lanes != 0) {
                // 当前候选或位图对应的实际账户 Lane 编号。
                int laneId = Long.numberOfTrailingZeros(lanes);
                lanes &= lanes - 1;
                if (canceledTriggers[laneId] == 0) continue;
                runtime.flushPublishedChanges(laneId);
                count = Math.addExact(count, canceledTriggers[laneId]);
            }
            if (count != 0) runtime.setMetadata(runtime.productLine(), Math.addExact(runtime.revision(), count));
        }
        if (metadataOrderIds == null) return;
        // 尚未处理的参与 Lane 位图，每次清除最低有效位。
        long lanes = requiredLaneMask;
        while (lanes != 0) {
            // 当前候选或位图对应的实际账户 Lane 编号。
            int laneId = Long.numberOfTrailingZeros(lanes);
            lanes &= lanes - 1;
            if (metadataOrders[laneId].isEmpty()) continue;
            runtime.orderChanges().adopt(laneId, metadataOrders[laneId]);
            usersByLane[laneId].forEach(runtime::changedUser);
        }
    }

    /**
     * 按实际 Lane 数量分配用户分组和间隔存放的完成数组，元数据及触发专用缓冲按需创建。
     * @param laneCount 当前运行时账户 Lane 数量
     */
    public LaneCommitEvent(int laneCount) {
        usersByLane = new LongArrayList[laneCount];
        completedLanes = new long[Math.multiplyExact(laneCount, CACHE_LINE_LONGS)];
        for (/* 当前候选或位图对应的实际账户 Lane 编号。 */ int laneId = 0; laneId < laneCount; laneId++) usersByLane[laneId] = new LongArrayList(4);
    }

    /**
     * 要求事件当前空闲、序号正数且目标非空，记录运行时和两个目标位图，并复制各参与 Lane 的用户编号列表；不在此实际入工作队列。
     * @param sequence 本事件唯一正数 Core 命令序号
     * @param laneMask 本次必须执行并回执的 Lane 位集合
     * @param routedUsers 调用方按 Lane 准备的用户编号分组，本入口复制参与列表
     * @param owner 拥有事件准备、完成观察及回收的运行时
     */
    public LaneCommitEvent prepare(long sequence, long laneMask, LongArrayList[] routedUsers,
                            TradingRuntimeState owner) {
        if (sequence <= 0 || laneMask == 0 || routedUsers == null || owner == null
                || coreSequence != 0 || completedLaneMask() != 0) {
            throw new IllegalStateException("invalid Account Lane commit event");
        }
        coreSequence = sequence;
        requiredLaneMask = laneMask;
        committedLaneMask = laneMask;
        runtime = owner;
        // 尚未处理的参与 Lane 位图，每次清除最低有效位。
        long lanes = laneMask;
        while (lanes != 0) {
            // 当前候选或位图对应的实际账户 Lane 编号。
            int laneId = Long.numberOfTrailingZeros(lanes);
            lanes &= lanes - 1;
            // 本 Lane 接收复制用户编号的复用目标列表。
            LongArrayList target = usersByLane[laneId];
            target.clear();
            target.addAll(routedUsers[laneId]);
        }
        return this;
    }

    /**
     * 要求结果所属 Lane 已参与事件，保存最终响应捕获目标及身份字典，并允许调用方指定真正要推进用户水位的提交位图。
     * @param target 需要在指定 Lane 捕获最终结果的响应目标
     * @param registry 解析最终响应身份信息的字典
     * @param laneId 负责捕获最终结果的参与 Lane 编号
     * @param commitMask 真正需要推进用户水位的 Lane 位集合
     */
    public void prepareResponse(LaneOrderResultTarget target, RuntimeIdentityRegistry registry,
                         int laneId, long commitMask) {
        if (target == null || registry == null || (requiredLaneMask & (1L << laneId)) == 0)
            throw new IllegalArgumentException("batch response requires its account Lane");
        resultTarget = target;
        identities = registry;
        resultLaneId = laneId;
        committedLaneMask = commitMask;
    }

    /**
     * 核对目标 Lane，依次关闭触发、补订单元数据、按提交位图推进用户水位并捕获最终结果；先保存通知目标，再 release 发布本 Lane 完成位，之后不再读取可被 Owner 回收的事件字段。
     * @param lane 本次执行回调实际持有的账户 Lane
     */
    public void execute(AccountLaneState lane) {
        // 当前候选或位图对应的实际账户 Lane 编号。
        int laneId = lane.laneId();
        // 当前执行 Lane 在参与及提交位图中的单个位。
        long laneBit = 1L << laneId;
        if ((requiredLaneMask & laneBit) == 0) {
            throw new IllegalStateException("Account Lane commit reached an unrelated lane");
        }
        cancelClosingTriggers(lane);
        stampMetadata(lane);
        if ((committedLaneMask & laneBit) != 0)
            runtime.applyLaneUsers(lane, usersByLane[laneId], coreSequence);
        if (resultTarget != null && laneId == resultLaneId)
            LaneOrderResultTarget.captureFinal(resultTarget, identities, lane);
        lane.recordSettlementOperation();
        // 当前 Lane 在间隔完成数组中的实际偏移。
        int completionOffset = laneId * CACHE_LINE_LONGS;
        if ((long) LONGS.getAcquire(completedLanes, completionOffset) != 0) {
            throw new IllegalStateException("Account Lane committed the same sequence twice");
        }
        // 先保存通知目标再发布完成位，Owner 可能立即收齐并重置事件，使 runtime 字段先于本 Lane 唤醒动作被清空。
        // 发布完成位之前保存的运行时通知目标，避免 Owner 立即回收事件后读到空 runtime。
        TradingRuntimeState completionRuntime = runtime;
        LONGS.setRelease(completedLanes, completionOffset, 1L);
        // 完成位才是交接依据，先发布可见完成位再唤醒 Owner；Owner 重新核对所有参与位后才继续。
        completionRuntime.signalOwnerCompletion();
    }

    /**
     * 以 acquire 读取各参与 Lane 已发布的完成标记，拼出当前观察到的完成位图。
     */
    public long completedLaneMask() {
        // 本次 acquire 观察到的完成 Lane 位集合。
        long mask = 0;
        // 尚未处理的参与 Lane 位图，每次清除最低有效位。
        long lanes = requiredLaneMask;
        while (lanes != 0) {
            // 当前候选或位图对应的实际账户 Lane 编号。
            int laneId = Long.numberOfTrailingZeros(lanes);
            lanes &= lanes - 1;
            if ((long) LONGS.getAcquire(completedLanes, laneId * CACHE_LINE_LONGS) != 0) {
                mask |= 1L << laneId;
            }
        }
        return mask;
    }
    /**
     * 检查观察到的完成位是否等于全部参与位；空闲事件两个位图都为零时也返回 true。
     */
    public boolean complete() {
        return completedLaneMask() == requiredLaneMask;
    }

    /**
     * 要求已收齐全部参与 Lane，再由 Owner 发布触发和元数据变化并重置事件；消费者仍在执行时不能提前调用。
     */
    public void publishAndClear() {
        if (!complete()) throw new IllegalStateException("incomplete Account Lane commit event");
        publishMetadata();
        reset();
    }

    /**
     * 只有尚未观察到任何完成位才允许重置；调用方还必须保证没有在途消费者，完成位为零本身不能证明任务尚未开始执行。
     */
    public void discard() {
        if (completedLaneMask() != 0) throw new IllegalStateException("started Account Lane commit cannot be discarded");
        reset();
    }

    /**
     * 清空本次参与 Lane 的用户、触发、元数据和完成标记，解除运行时、响应及字典引用，保留可复用数组和缓冲容量。
     */
    private void reset() {
        // 尚未处理的参与 Lane 位图，每次清除最低有效位。
        long lanes = requiredLaneMask;
        if (triggerCancelIds != null) {
            // 重置时还需清理触发候选与关闭计数的 Lane 位图。
            long triggerLanes = lanes;
            while (triggerLanes != 0) {
                // 当前候选或位图对应的实际账户 Lane 编号。
                int laneId = Long.numberOfTrailingZeros(triggerLanes);
                triggerLanes &= triggerLanes - 1;
                if (triggerCancelIds[laneId] != null) triggerCancelIds[laneId].clear();
                canceledTriggers[laneId] = 0;
            }
        }

        if (metadataOrderIds != null) {
            // 重置时还需清理订单元数据输出的 Lane 位图。
            long metadataLanes = lanes;
            while (metadataLanes != 0) {
                // 当前候选或位图对应的实际账户 Lane 编号。
                int laneId = Long.numberOfTrailingZeros(metadataLanes);
                metadataLanes &= metadataLanes - 1;
                if (metadataOrders[laneId] != null) metadataOrders[laneId].clear();
            }
            metadataOrderIds.clear();
        }
        metadataTimestamp = metadataPosition = 0;

        while (lanes != 0) {
            // 当前候选或位图对应的实际账户 Lane 编号。
            int laneId = Long.numberOfTrailingZeros(lanes);
            lanes &= lanes - 1;
            usersByLane[laneId].clear();
            LONGS.setRelease(completedLanes, laneId * CACHE_LINE_LONGS, 0L);
        }
        runtime = null;
        coreSequence = 0;
        requiredLaneMask = 0;
        committedLaneMask = 0;
        resultTarget = null;
        identities = null;
        resultLaneId = 0;
    }
}
