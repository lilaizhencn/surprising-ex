package com.surprising.aeron.service.core.change;

import com.surprising.aeron.bootstrap.account.SettlementLaneWorker;
import com.surprising.aeron.model.CoreTriggerOrderState;
import com.surprising.aeron.service.account.AccountPartition;
import com.surprising.aeron.service.account.UserAccount;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.order.*;
import com.surprising.aeron.service.order.Order;
import com.surprising.aeron.service.order.OrderCommand.ResultTarget;
import com.surprising.aeron.service.order.OrderFundsReservation;
import com.surprising.aeron.service.position.Position;
import com.surprising.aeron.service.risk.RiskScanner;
import com.surprising.aeron.service.risk.RiskMarginCalculator;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.eclipse.collections.api.map.primitive.LongLongMap;
import org.eclipse.collections.impl.list.mutable.primitive.LongArrayList;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;

/**
 * 一个 Core 序号对受影响账户 Lane 的异步提交扇出。各 Lane 完成触发关闭、订单元数据、用户水位及结果捕获后发布自己的完成位，Owner 收齐全部 required 位才发布和回收。requiredLaneMask 是必须回执的分区，committedLaneMask 决定其中哪些分区还要推进用户水位。
 */
public final class AccountPartitionCommit implements SettlementLaneWorker.Command {
    /** 完成标记之间间隔十六个 long，避免各 Lane 紧邻写同一小片存储。 */
    private static final int CACHE_LINE_LONGS = 16;
    /** 完成数组元素的 VarHandle，Lane release 写入、Owner acquire 读取。 */
    private static final VarHandle LONGS = MethodHandles.arrayElementVarHandle(long[].class);

    /** 每个 Lane 在本提交边界需要推进的用户编号列表，元数据补写可追加相关用户。 */
    private final LongArrayList[] usersByLane;
    /** 各 Lane 间隔保存的完成标记，零未发布、非零已经发布本事件结果。 */
    private final long[] completedLanes;
    /** 当前事件关联的运行时，Owner 重置后可清空，所以 Lane 发布前先保存通知目标。 */
    private TradingAccountRuntime runtime;
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
    private OrderCommand.ResultTarget resultTarget;
    /** 最终响应捕获需要的身份字典，回收事件时解除引用。 */
    private RuntimeIdentityRegistry identities;
    /** 负责捕获最终订单响应的参与 Lane 编号。 */
    private int resultLaneId;

    /** 准备阶段按已捕获的用户归属分组，只向所属 Lane 发送候选编号。 */
    private LongArrayList[] metadataOrderIds;
    /** 各 Lane 已独立冻结的元数据订单后值物理缓冲，完成后交 Owner 接管。 */
    private StateChangeBuffer.Orders[] metadataOrders;
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
    private void cancelClosingTriggers(AccountPartition lane) {
        // 当前候选或位图对应的实际账户 Lane 编号。
        int laneId = lane.laneId();
        if (triggerCancelIds == null || triggerCancelIds[laneId] == null) return;
        runtime.enterLaneCommandScope(lane);
        try {
            // 当前 Lane 的触发列表或共享元数据候选列表，只访问本事件有效项。
            LongArrayList ids = triggerCancelIds[laneId];
            for (/* 当前候选列表中的连续下标。 */ int index = 0; index < ids.size(); index++)
                if (runtime.cancelPendingTriggerOrderForCommit(ids.get(index)))
                    canceledTriggers[laneId]++;
        } finally { runtime.exitLaneCommandScope(lane); }
    }

    /** 随事件复用的分组回调，不逐命令创建捕获函数。 */
    private final org.eclipse.collections.api.block.procedure.primitive.LongLongProcedure collectMetadataOrder = (orderId, userId) -> {
        if (userId == 0) return;
        int laneId = runtime.topology().accountLaneId(userId);
        if ((requiredLaneMask & (1L << laneId)) == 0)
            throw new IllegalStateException("metadata order has no account commit Lane");
        if (metadataOrderIds[laneId] == null) metadataOrderIds[laneId] = new LongArrayList(4);
        metadataOrderIds[laneId].add(orderId);
    };

    /**
     * 复制已捕获的订单及用户归属，按 Lane 准备实际候选容量；零用户表示候选已退休。
     * 归属来自 Owner 已收集的冻结后值或请求明确身份，不读取 Lane 活订单表。
     */
    public void prepareMetadata(LongLongMap orderUsers, long timestamp, long position) {
        if (orderUsers == null || orderUsers.isEmpty()) return;
        if (timestamp < 0 || position < 0) throw new IllegalArgumentException("invalid commit metadata");
        metadataTimestamp = timestamp;
        metadataPosition = position;
        if (metadataOrderIds == null) {
            metadataOrderIds = new LongArrayList[usersByLane.length];
            metadataOrders = new StateChangeBuffer.Orders[usersByLane.length];
        }
        orderUsers.forEachKeyValue(collectMetadataOrder);
        long lanes = requiredLaneMask;
        while (lanes != 0) {
            int laneId = Long.numberOfTrailingZeros(lanes);
            lanes &= lanes - 1;
            LongArrayList ids = metadataOrderIds[laneId];
            if (ids == null || ids.isEmpty()) continue;
            if (metadataOrders[laneId] == null) metadataOrders[laneId] = new StateChangeBuffer.Orders();
            metadataOrders[laneId].ensureCapacity(ids.size());
        }
    }

    /**
     * 所属 Lane 只处理自己持有且元数据不同的候选订单，保存回滚前值后原位补时间和位置，并把独立订单后值及相关用户登记到本事件。
     * @param lane 本次执行回调实际持有的账户 Lane
     */
    private void stampMetadata(AccountPartition lane) {
        // 当前候选或位图对应的实际账户 Lane 编号。
        int laneId = lane.laneId();
        if (metadataOrderIds == null || metadataOrderIds[laneId] == null) return;
        LongArrayList ids = metadataOrderIds[laneId];
        runtime.enterLaneCommandScope(lane);
        try {
            for (/* 当前候选列表中的连续下标。 */ int index = 0; index < ids.size(); index++) {
                // 当前触发或元数据候选的业务编号。
                long id = ids.get(index);
                // 当前 Lane 的权威记录，候选在执行前可能已退休。
                Order order = lane.order(id);
                if (order == null) continue;
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
            if (metadataOrders[laneId] == null || metadataOrders[laneId].isEmpty()) continue;
            runtime.orderChanges().adopt(laneId, metadataOrders[laneId]);
            usersByLane[laneId].forEach(runtime::changedUser);
        }
    }

    /**
     * 按实际 Lane 数量分配用户分组和间隔存放的完成数组，元数据及触发专用缓冲按需创建。
     * @param laneCount 当前运行时账户 Lane 数量
     */
    public AccountPartitionCommit(int laneCount) {
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
    public AccountPartitionCommit prepare(long sequence, long laneMask, LongArrayList[] routedUsers,
                            TradingAccountRuntime owner) {
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
    public void prepareResponse(OrderCommand.ResultTarget target, RuntimeIdentityRegistry registry,
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
    public void execute(AccountPartition lane) {
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
            OrderCommand.ResultTarget.captureFinal(resultTarget, identities, lane);
        lane.recordSettlementOperation();
        // 当前 Lane 在间隔完成数组中的实际偏移。
        int completionOffset = laneId * CACHE_LINE_LONGS;
        if ((long) LONGS.getAcquire(completedLanes, completionOffset) != 0) {
            throw new IllegalStateException("Account Lane committed the same sequence twice");
        }

        LONGS.setRelease(completedLanes, completionOffset, 1L);

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
                if (metadataOrderIds[laneId] != null) metadataOrderIds[laneId].clear();
            }
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


/**
 * 一个命令在一个账户 Lane 上的变化收据。Lane 保存值或待冻结活引用、终态订单身份及本边界持仓计数，发布完成后 Owner 才读取和接管。订单与持仓专用缓冲可整体交换，用户和预留元数据按各自入口收集；本类不重新计算成交或资金。
 */
public static final class Delta {
    /** 是否已冻结本收据订单预留并准备终态身份，终态提交要求此标记。 */
    private boolean publicationPrepared;
    /** Lane 已收集的终态订单编号列，只读取 terminalOrderCount 之前的项。 */
    private long[] terminalOrderIds = new long[4];
    /** 与终态编号同下标的用户编号列。 */
    private long[] terminalOrderUsers = new long[4];
    /** 与终态编号同下标的客户号文本列，清理时释放有效引用。 */
    private String[] terminalOrderClients = new String[4];
    /**  本收据已保存的有效终态身份数量。 返回本收据已收集的终态身份有效数量。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private int terminalOrderCount;
    /**
     *  Lane 独立写入并在完成位之后交给 Owner 的待准入完成数。
     * 返回该 Lane 本收据记录的已完成待准入数量。
     * 保存 Lane 已完成的待准入数量回执，不在此修改预留权威表。
     * @param completedPending 本 Lane 在该边界完成的待准入条数
     */
    @Accessors(fluent = true, chain = false)
    @Getter
    @Setter
    int completedPending;

    /** 等待消费者归还 Lane 的已退休订单引用数组。 */
    private Order[] retiredOrders = new Order[4];
    /** 待归还订单引用数组的有效长度。 */
    private int retiredOrderCount;

    /**
     * 追加已脱离业务表的订单回收引用，需要时增长数组；是否跨过最终退休水位由后续 Lane 回收池判断。
     * @param order 已经脱离业务表、准备由本收据暂存回收引用的订单
     */
    public void retiredOrder(Order order) {
        if (retiredOrderCount == retiredOrders.length)
            retiredOrders = java.util.Arrays.copyOf(retiredOrders, retiredOrders.length * 2);
        retiredOrders[retiredOrderCount++] = order;
    }

    /**
     * 把本收据暂存的订单引用交回所属 Lane 回收队列并清空有效引用，后续复用仍要等待订单退休水位。
     * @param lane 实际拥有这些账户状态、负责捕获或接收回收对象的 Lane
     */
    public void returnRetiredOrders(AccountPartition lane) {
        for (/* 当前正在消费的收据连续有效槽。 */ int index = 0; index < retiredOrderCount; index++) {
            lane.returnRetiredOrder(retiredOrders[index]);
            retiredOrders[index] = null;
        }
        retiredOrderCount = 0;
    }

    /**
     * 按二次幂预留终态订单编号、用户和客户号数组容量，不改变当前有效终态数量。
     * @param expectedCount 预期需要保存的终态订单身份数量
     */
    public void ensureTerminalCapacity(int expectedCount) {
        if (expectedCount <= terminalOrderIds.length) return;
        // 终态身份三列需要共同扩展到的二次幂容量。
        int capacity = terminalOrderIds.length;
        while (capacity < expectedCount) capacity = Math.multiplyExact(capacity, 2);
        terminalOrderIds = java.util.Arrays.copyOf(terminalOrderIds, capacity);
        terminalOrderUsers = java.util.Arrays.copyOf(terminalOrderUsers, capacity);
        terminalOrderClients = java.util.Arrays.copyOf(terminalOrderClients, capacity);
    }

    /**
     * 把订单编号、用户编号和客户号文本复制到本收据的身份列，全部写完再增加有效计数，不保存整份终态订单副本。
     * @param value 要摘取身份信息的非空订单，终态判断由调用方负责
     */
    public void recordTerminalOrder(Order value) {
        if (terminalOrderCount == terminalOrderIds.length) {
            ensureTerminalCapacity(Math.addExact(terminalOrderCount, 1));
        }
        terminalOrderIds[terminalOrderCount] = value.orderId();
        terminalOrderUsers[terminalOrderCount] = value.userId();
        terminalOrderClients[terminalOrderCount] = value.clientOrderId();
        terminalOrderCount++;
    }

    /**
     * 拒绝重复准备；只有尚无终态身份记录时才从变化中补收终态标识，再冻结订单和预留值，最后设置已准备标记。
     */
    public void preparePublication() {
        if (publicationPrepared) throw new IllegalStateException("Lane publication already prepared");
        if (terminalOrderCount == 0) {
            orders.forEach((/* 当前订单或预留变化对应的订单编号。 */ orderId, /* 当前订单变化值，只有非空且终态才补记终态身份。 */ order) -> {
                if (order != null && order.status().terminal()) recordTerminalOrder(order);
            });
        }
        orders.freezeValues();
        reservations.freezeValues();
        publicationPrepared = true;
    }

    /**
     * 读取有效终态身份槽的订单编号，调用方应以终态计数限制下标。
     * @param index 终态身份有效列中的下标
     */
    public long terminalOrderId(int index) { return terminalOrderIds[index]; }
    /**
     * 读取有效终态身份槽的用户编号。
     * @param index 终态身份有效列中的下标
     */
    public long terminalOrderUser(int index) { return terminalOrderUsers[index]; }
    /**
     * 读取有效终态身份槽的客户号文本，不从后续活订单重新取值。
     * @param index 终态身份有效列中的下标
     */
    public String terminalOrderClient(int index) { return terminalOrderClients[index]; }

    /** 按需分配的触发单变化列，null 表示此收据尚未使用该列。 */
    StateChangeBuffer<CoreTriggerOrderState> triggers;
    /** 按需分配的算法单变化列。 */
    StateChangeBuffer<com.surprising.aeron.model.CoreAlgoOrderState> algos;
    /**
     *  本边界实际关闭的触发记录条数，Owner 收集后增加产品修订。
     * 返回收据保存的关闭触发单数量。
     * 记录此收据实际关闭触发单的条数，后续 Owner 据此增加修订。
     * @param closedTriggerCount 本收据实际关闭的触发记录条数
     */
    @Accessors(fluent = true, chain = false)
    @Getter
    @Setter
    int closedTriggerCount;
    /** 本收据独立复制的用户命令值缓冲。 */
    final StateChangeBuffer.Users users = new StateChangeBuffer.Users();
    /**  Lane 活引用在完成位前冻结的订单变化缓冲，可整体交给 Owner。 返回当前收据订单专用变化缓冲，不能在已交接后继续读写旧租约。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    final StateChangeBuffer.Orders orders = new StateChangeBuffer.Orders();
    /** Lane 活引用在完成位前冻结的预留后值缓冲。 */
    final StateChangeBuffer.Reservations reservations = new StateChangeBuffer.Reservations();
    /**  持仓发布值与预计算索引的专用变化缓冲。 返回当前收据持仓专用变化缓冲，仍受 Lane 到 Owner 的租约约束。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    final StateChangeBuffer.Positions positions = new StateChangeBuffer.Positions();
    /** 本边界强平值变化，null 代表删除。 */
    final StateChangeBuffer<RiskScanner.LiquidationOrder> liquidations = new StateChangeBuffer<>();
    /** 本次需要发布的风险快照变化。 */
    /** 风险后值与可选布尔删除证明，证明由本 Lane 结束时读取持仓存在性获得。 */
    final StateChangeBuffer.Indexed<RiskMarginCalculator.Snapshot, Boolean> riskSnapshots = new StateChangeBuffer.Indexed<>();
    /** 下游需要知道的已移除订单路由编号集合。 */
    final LongHashSet removedOrderRoutes = new LongHashSet();
    /** 本边界记录的预留路由移除编号集合。 */
    final LongHashSet removedReservationRoutes = new LongHashSet();

    /**
     * 为单用户批量准入预留用户、订单及预留变化容量。
     * @param expectedOrders 本边界预计涉及的订单数量
     */
    public void ensureAdmissionCapacity(int expectedOrders) {
        users.ensureCapacity(1);
        orders.ensureCapacity(expectedOrders);
        reservations.ensureCapacity(expectedOrders);
    }

    /**
     * 按预期订单数量预留用户、订单、预留和持仓变化容量，提前准备容器容量但不创建业务实体。
     * @param expectedOrders 本边界预计涉及的订单数量
     */
    public void ensureOrderCapacity(int expectedOrders) {
        users.ensureCapacity(expectedOrders * 2);
        orders.ensureCapacity(expectedOrders);
        reservations.ensureCapacity(expectedOrders);
        positions.ensureCapacity(expectedOrders * 2);
    }

    /**
     * 把当前用户值复制到本收据独占的命令槽，避免后续原位用户变更覆盖较早回执。
     * @param key 当前用户、订单、预留、持仓、强平或风险实体的原语键，具体含义由当前入口固定
     * @param value 当前入口要记录的业务后值，null 表示删除；是否立即复制由该列缓冲决定
     */
    public void putUser(long key, UserAccount value) {
        users.put(key, value);
    }

    /**
     * 先登记 Lane 活订单引用，完成发布前由准备或控制冻结入口复制成命令值。
     * @param key 当前用户、订单、预留、持仓、强平或风险实体的原语键，具体含义由当前入口固定
     * @param value 当前入口要记录的业务后值，null 表示删除；是否立即复制由该列缓冲决定
     */
    public void putOrder(long key, Order value) {
        orders.putLive(key, value);
    }

    /**
     * 先登记 Lane 活预留引用，完成发布前再冻结为本收据发布值。
     * @param key 当前用户、订单、预留、持仓、强平或风险实体的原语键，具体含义由当前入口固定
     * @param value 当前入口要记录的业务后值，null 表示删除；是否立即复制由该列缓冲决定
     */
    public void putReservation(long key, OrderFundsReservation value) {
        reservations.putLive(key, value);
    }

    /**
     * 由账户工作线程在发布控制完成位之前冻结订单和预留后值。
     */
    public void freezeControlValues() { orders.freezeValues(); reservations.freezeValues(); }

    /**
     * 立即把持仓复制为本物理变化缓冲的发布值。
     * @param key 当前用户、订单、预留、持仓、强平或风险实体的原语键，具体含义由当前入口固定
     * @param value 当前入口要记录的业务后值，null 表示删除；是否立即复制由该列缓冲决定
     */
    public void putPosition(long key, Position value) {
        positions.put(key, value);
    }

    /**
     * 记录当前强平后值引用或 null 删除变化，不在本入口执行强平。
     * @param key 当前用户、订单、预留、持仓、强平或风险实体的原语键，具体含义由当前入口固定
     * @param value 当前入口要记录的业务后值，null 表示删除；是否立即复制由该列缓冲决定
     */
    public void putLiquidation(long key, RiskScanner.LiquidationOrder value) {
        liquidations.put(key, value);
    }

    /**
     * 记录风险后值并使该键旧预计算列失效，删除证明在 Lane 命令结束时采集。
     * @param key 当前用户、订单、预留、持仓、强平或风险实体的原语键，具体含义由当前入口固定
     * @param value 当前入口要记录的业务后值，null 表示删除；是否立即复制由该列缓冲决定
     */
    public void putRiskSnapshot(long key, RiskMarginCalculator.Snapshot value) {
        riskSnapshots.put(key, value);
    }

    /**
     * 要求所属 Lane 线程，对风险删除项保存此时持仓是否也不存在的证明，供后续身份回收。
     * @param lane 实际拥有这些账户状态、负责捕获或接收回收对象的 Lane
     */
    public void preparePositionRetirement(AccountPartition lane) {
        lane.assertOwner();
        for (/* 当前正在消费的收据连续有效槽。 */ int index = 0; index < riskSnapshots.size(); index++) {
            if (riskSnapshots.valueAt(index) != null) continue;
            // 当前风险变化对应的运行时持仓键。
            long key = riskSnapshots.keyAt(index);
            riskSnapshots.putPrepared(key, lane.position(key) == null);
        }
    }

    /**
     * 登记需要交给下游输出的订单路由移除编号，不直接操作全局路由表。
     * @param orderId 需要报告移除路由的订单编号
     */
    public void removeOrderRoute(long orderId) { removedOrderRoutes.add(orderId); }
    /**
     * 登记本边界预留路由移除编号，后续收集路径消费并清理该集合。
     * @param orderId 需要报告移除路由的订单编号
     */
    public void removeReservationRoute(long orderId) { removedReservationRoutes.add(orderId); }

    /**
     * 收集本方法覆盖的用户及订单变化键，清理相关业务缓冲，并更新风险持仓身份集合；不等同于完整收据发布或 clear，触发变化和终态身份另有边界。
     * @param laneId 接口保留的 Lane 编号，本方法实现不读取此参数
     * @param state 接收本边界发布变化的 Owner 运行时
     * @param changedUsers 可选的本命令变化用户键集合，不需要时为 null
     * @param changedOrders 可选的本命令变化订单键集合，不需要时为 null
     */
    public void drainTo(TradingAccountRuntime state,
                        com.surprising.aeron.support.collection.PrimitiveLongChangeSet changedUsers,
                        com.surprising.aeron.support.collection.PrimitiveLongChangeSet changedOrders) {
        if (changedUsers != null || changedOrders != null) {
            users.forEach((/* 本收据用户变化对应的用户编号。 */ userId, /* 本次遍历只收集业务键，不读取回调传入的值。 */ ignored) -> {
                if (changedUsers != null) changedUsers.add(userId);
            });
            orders.forEach((/* 当前订单或预留变化对应的订单编号。 */ orderId, /* 本次遍历只收集业务键，不读取回调传入的值。 */ ignored) -> {
                if (changedOrders != null) changedOrders.add(orderId);
            });
            reservations.forEach((/* 当前订单或预留变化对应的订单编号。 */ orderId, /* 本收据预留后值，非空时可从它得到用户编号。 */ reservation) -> {
                if (changedOrders != null) changedOrders.add(orderId);
                if (changedUsers != null && reservation != null) changedUsers.add(reservation.userId());
            });
            positions.forEach((/* 当前持仓变化的原语键，收集用户键时本回调不读取此键。 */ positionKey, /* 本收据的持仓发布值，非空时用于收集所属用户。 */ position) -> {
                if (changedUsers != null && position != null) changedUsers.add(position.userId());
            });
            if (changedOrders != null) removedOrderRoutes.forEach(changedOrders::add);
        }
        users.clear();
        orders.clear();
        reservations.clear();
        positions.clear();
        liquidations.clear();
        for (/* 当前正在消费的收据连续有效槽。 */ int index = 0; index < riskSnapshots.size(); index++) {
            // 当前风险变化对应的运行时持仓键。
            long key = riskSnapshots.keyAt(index);
            if (riskSnapshots.valueAt(index) == null) state.removeRiskPositionIdentity(key);
            else state.addRiskPositionIdentity(key);
        }
        riskSnapshots.clear();
        if (!removedOrderRoutes.isEmpty()) removedOrderRoutes.clear();
        if (!removedReservationRoutes.isEmpty()) removedReservationRoutes.clear();
    }

    /**
     * 先发布触发及关闭计数，要求终态准备完成，再收集用户及预留元数据和终态身份，传递强平风险变化，最后接管订单与持仓物理缓冲。
     * @param state 接收本边界发布变化的 Owner 运行时
     * @param laneId 当前收据实际所属的账户 Lane 编号
     * @param terminalOrderSink 可选的终态身份批量消费者，读取本收据已保存的原语身份列
     * @param changedUsers 可选的本命令变化用户键集合，不需要时为 null
     * @param changedOrders 可选的本命令变化订单键集合，不需要时为 null
     */
    public void commitTerminalToOwner(TradingAccountRuntime state, int laneId,
            TradingAccountRuntime.TerminalOrderSink terminalOrderSink,
            com.surprising.aeron.support.collection.PrimitiveLongChangeSet changedUsers,
            com.surprising.aeron.support.collection.PrimitiveLongChangeSet changedOrders) {
        publishTriggersToOwner(state);
        if (closedTriggerCount != 0) {
            state.restoreRevision(Math.addExact(state.revision(), closedTriggerCount));
            closedTriggerCount = 0;
        }
        if (!publicationPrepared) throw new IllegalStateException("Lane terminal publication is not prepared");
        publishPreparedToOwner(state, changedUsers, changedOrders);
        if (terminalOrderSink != null && terminalOrderCount != 0)
            terminalOrderSink.acceptBatch(this);
        for (/* 当前正在消费的收据连续有效槽。 */ int index = 0; index < liquidations.size(); index++)
            state.changedLiquidations().put(liquidations.keyAt(index), liquidations.valueAt(index));
        liquidations.clear();
        for (/* 当前正在消费的收据连续有效槽。 */ int index = 0; index < riskSnapshots.size(); index++) {
            // 当前风险变化对应的运行时持仓键。
            long key = riskSnapshots.keyAt(index);
            // 当前强平、预留、持仓或风险变化值，null 保留删除含义。
            RiskMarginCalculator.Snapshot value = riskSnapshots.valueAt(index);
            state.changedRiskSnapshots().putIndexed(key, value, riskSnapshots.hasPreparedAt(index), riskSnapshots.preparedAt(index));
            if (value == null) state.removeRiskPositionIdentity(key);
            else state.addRiskPositionIdentity(key);
        }
        riskSnapshots.clear();
        state.orderChanges().adopt(laneId, orders);
        state.positionChanges().adopt(laneId, positions);
    }

    /**
     * 记录用户及预留输出和可选变化键，清理已消费的用户预留及路由移除集合，并解除准备标记；订单持仓仍留待后续接管。
     * @param state 接收本边界发布变化的 Owner 运行时
     * @param changedUsers 可选的本命令变化用户键集合，不需要时为 null
     * @param changedOrders 可选的本命令变化订单键集合，不需要时为 null
     */
    private void publishPreparedToOwner(
            TradingAccountRuntime state,
            com.surprising.aeron.support.collection.PrimitiveLongChangeSet changedUsers,
            com.surprising.aeron.support.collection.PrimitiveLongChangeSet changedOrders) {
        // 这里只收集本命令变化键及用户、预留元数据，订单和持仓另走物理缓冲交接。
        for (/* 当前正在消费的收据连续有效槽。 */ int index = 0; index < users.size(); index++) {
            // 当前用户、订单、预留或持仓列中的业务键。
            long id = users.keyAt(index);
            if (changedUsers != null) changedUsers.add(id);
            state.changedUser(id);
            state.captureUserMetadata(users.valueAt(index));
        }
        users.clear();
        for (/* 当前正在消费的收据连续有效槽。 */ int index = 0; index < orders.size(); index++) {
            // 当前用户、订单、预留或持仓列中的业务键。
            long id = orders.keyAt(index);
            if (changedOrders != null) changedOrders.add(id);
        }
        for (/* 当前正在消费的收据连续有效槽。 */ int index = 0; index < reservations.size(); index++) {
            // 当前用户、订单、预留或持仓列中的业务键。
            long id = reservations.keyAt(index);
            // 当前强平、预留、持仓或风险变化值，null 保留删除含义。
            OrderFundsReservation value = reservations.valueAt(index);
            if (changedOrders != null) changedOrders.add(id);
            if (changedUsers != null && value != null) changedUsers.add(value.userId());
            state.changedReservation(id);
            state.captureReservation(value);
        }
        reservations.clear();
        for (/* 当前正在消费的收据连续有效槽。 */ int index = 0; index < positions.size(); index++) {
            // 当前用户、订单、预留或持仓列中的业务键。
            long id = positions.keyAt(index);
            // 当前强平、预留、持仓或风险变化值，null 保留删除含义。
            Position value = positions.valueAt(index);
            if (changedUsers != null && value != null) changedUsers.add(value.userId());
        }
        if (!removedOrderRoutes.isEmpty()) {
            if (changedOrders != null) removedOrderRoutes.forEach(changedOrders::add);
            removedOrderRoutes.clear();
        }
        if (!removedReservationRoutes.isEmpty()) {
            removedReservationRoutes.clear();
        }
        publicationPrepared = false;
    }

    /**
     * 把已准备的批量准入变化和触发变化交给 Owner，并接管订单和持仓缓冲；部分源列仍由外层释放时 clear，本方法不自行冻结活引用。
     * @param state 接收本边界发布变化的 Owner 运行时
     * @param laneId 当前收据实际所属的账户 Lane 编号
     */
    public void commitBatchAdmissionToOwner(TradingAccountRuntime state, int laneId) {
        if (state == null || laneId < 0 || laneId >= state.accountLaneCount()) {
            throw new IllegalArgumentException("invalid batch admission owner handoff");
        }
        publishTriggersToOwner(state);
        if (closedTriggerCount != 0) {
            state.restoreRevision(Math.addExact(state.revision(), closedTriggerCount));
            closedTriggerCount = 0;
        }
        for (/* 当前正在消费的收据连续有效槽。 */ int index = 0; index < users.size(); index++) state.changedUser(users.keyAt(index));
        for (/* 当前正在消费的收据连续有效槽。 */ int index = 0; index < reservations.size(); index++) state.changedReservation(reservations.keyAt(index));
        state.orderChanges().adopt(laneId, orders);
        state.positionChanges().adopt(laneId, positions);
        for (/* 当前正在消费的收据连续有效槽。 */ int index = 0; index < liquidations.size(); index++)
            state.changedLiquidations().put(liquidations.keyAt(index), liquidations.valueAt(index));
        for (/* 当前正在消费的收据连续有效槽。 */ int index = 0; index < riskSnapshots.size(); index++)
            state.changedRiskSnapshots().putIndexed(riskSnapshots.keyAt(index), riskSnapshots.valueAt(index),
                    riskSnapshots.hasPreparedAt(index), riskSnapshots.preparedAt(index));
        if (!removedOrderRoutes.isEmpty()) removedOrderRoutes.clear();
        if (!removedReservationRoutes.isEmpty()) removedReservationRoutes.clear();
    }

    /**
     * 按需创建算法单变化列并记录最新后值，null 表示删除。
     * @param id 算法或触发记录的业务编号
     * @param value 当前入口要记录的业务后值，null 表示删除；是否立即复制由该列缓冲决定
     */
    public void putAlgo(long id, com.surprising.aeron.model.CoreAlgoOrderState value) {
        if (algos == null) algos = new StateChangeBuffer<>();
        algos.put(id, value);
    }

    /**
     * 按需创建触发变化列并记录最新后值，null 表示删除。
     * @param id 算法或触发记录的业务编号
     * @param value 当前入口要记录的业务后值，null 表示删除；是否立即复制由该列缓冲决定
     */
    public void putTrigger(long id, CoreTriggerOrderState value) {
        if (triggers == null) triggers = new StateChangeBuffer<>();
        triggers.put(id, value);
    }

    /**
     * 把算法与触发变化交入 Owner 变化集合，再清空源列，保持原命令顺序。
     * @param state 接收本边界发布变化的 Owner 运行时
     */
    public void publishTriggersToOwner(TradingAccountRuntime state) {
        if (algos != null) algos.drainTo(state.changedAlgoOrders()::put);
        if (triggers == null || triggers.isEmpty()) return;
        triggers.forEach((/* 当前用户、订单、预留或持仓列中的业务键。 */ id, /* 当前强平、预留、持仓或风险变化值，null 保留删除含义。 */ value) -> {
            state.changedTriggerOrders().put(id, value);
        });
        triggers.clear();
    }

    /**
     * 收集控制任务的用户及预留元数据，接管已冻结的订单持仓变化并传递强平风险后值；剩余源列生命周期由外层 clear 管理。
     * @param state 接收本边界发布变化的 Owner 运行时
     * @param laneId 当前收据实际所属的账户 Lane 编号
     */
    public void recordControlChanges(TradingAccountRuntime state, int laneId) {
        for (/* 当前正在消费的收据连续有效槽。 */ int index = 0; index < users.size(); index++) {
            state.changedUser(users.keyAt(index));
            state.captureUserMetadata(users.valueAt(index));
        }
        for (/* 当前正在消费的收据连续有效槽。 */ int index = 0; index < reservations.size(); index++) {
            state.changedReservation(reservations.keyAt(index));
            state.captureReservation(reservations.valueAt(index));
        }
        state.orderChanges().adopt(laneId, orders);
        state.positionChanges().adopt(laneId, positions);
        for (/* 当前正在消费的收据连续有效槽。 */ int index = 0; index < liquidations.size(); index++)
            state.changedLiquidations().put(liquidations.keyAt(index), liquidations.valueAt(index));
        for (/* 当前正在消费的收据连续有效槽。 */ int index = 0; index < riskSnapshots.size(); index++)
            state.changedRiskSnapshots().putIndexed(riskSnapshots.keyAt(index), riskSnapshots.valueAt(index),
                    riskSnapshots.hasPreparedAt(index), riskSnapshots.preparedAt(index));
    }

    /**
     * 重置计数、终态身份和变化列，释放有效引用并保留容量；待回收订单只是解除引用，不会自动调用 returnRetiredOrders。
     */
    public void clear() {
        completedPending = 0;
        closedTriggerCount = 0;
        java.util.Arrays.fill(retiredOrders, 0, retiredOrderCount, null);
        retiredOrderCount = 0;
        if (triggers != null) triggers.clear();
        if (algos != null) algos.clear();
        publicationPrepared = false;
        java.util.Arrays.fill(terminalOrderClients, 0, terminalOrderCount, null);
        // 原语列不持有引用；count 是唯一有效边界，recordTerminalOrder 先完整覆盖再递增。
        terminalOrderCount = 0;
        users.clear();
        orders.clear();
        reservations.clear();
        positions.clear();
        liquidations.clear();
        riskSnapshots.clear();
        if (!removedOrderRoutes.isEmpty()) removedOrderRoutes.clear();
        if (!removedReservationRoutes.isEmpty()) removedReservationRoutes.clear();
    }

}
}
