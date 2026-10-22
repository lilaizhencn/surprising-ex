package com.surprising.aeron.service.order.state;

import com.surprising.aeron.bootstrap.account.SettlementLaneWorker;
import com.surprising.aeron.model.CoreOrderStatus;
import com.surprising.aeron.service.account.AccountLaneState;
import com.surprising.aeron.service.core.TradingRuntimeState;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.settlement.MatcherSettlementChanges;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

/**
 * 一条 Core 序号内、一个用户所属账户 Lane 的单笔或多笔撤单事件。按原 Matcher 事实处理订单和冻结，捕获终态变化，按配置自行提交 Lane 或交给批次统一提交，最后发布完成回执。
 */
public final class LaneCancelEvent implements SettlementLaneWorker.Command {
    /** 提供原结算范围、取消和完成通知的所属运行时。 */
    private TradingRuntimeState runtime;
    /** 本命令原有结算变化缓冲，Lane 写完后由 Owner 接管一次。 */
    private MatcherSettlementChanges changes;
    /**  最终结果及变更捕获采用的当前产品身份表。 返回本次借用的产品身份表，仅在原事件生命周期内使用。 */
    @Accessors(fluent = true, chain = false)
    @Getter(value = AccessLevel.PACKAGE)
    private RuntimeIdentityRegistry identities;
    /** 本次撤单事件的正数 Core 序号，清理后归零防止重复准备。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long coreSequence;
    /** 待撤订单共同所属用户编号。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long userId;
    /** 事件自己保存的编号数组，准备时复制输入，有效前缀由 orderCount 决定。 */
    private long[] orderIds;
    /** 本次有效撤单编号数量。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private int orderCount;
    /** 本次撤单终态采用的集群业务时间。 */
    private long commitTimestamp;
    /** 本次撤单终态采用的集群日志位置。 */
    private long commitClusterPosition;
    /** 唯一目标账户 Lane 编号。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private int laneId;
    /** 是否由此事件自行推进 Lane 应用和提交水位，false 时由原批次边界负责。 */
    private boolean commitLane;
    /**
     *  原命令拥有的结果接收槽，Lane 在发布完成前捕获并准备响应。
     * 绑定原命令的结果接收槽，必须在 Lane 使用前由原派发流程准备好。
     * @param resultTarget 本命令预先准备的结果槽，接收最终订单和已编码响应
     */
    @Accessors(fluent = true, chain = false)
    @Setter
    LaneOrderResultTarget resultTarget;
    /** Lane 完成所有变化和结果捕获后的可见性标记，回收前必须等到它。 */
    @SuppressWarnings("unused")
    private volatile boolean completed;

    /**
     * 为单笔撤单准备可复用编号数组，并沿用多笔入口；默认由此事件提交目标账户 Lane。
     * @param sequence 本次事件归属的正数 Core 命令序号
     * @param ownerUserId 待撤订单共同所属用户的正数编号
     * @param targetOrderId 单笔撤单的正数订单编号
     * @param timestamp 写入撤单终态的非负业务时间
     * @param clusterPosition 写入撤单终态的非负集群日志位置
     * @param ownerLaneId 本次用户所属账户 Lane 编号
     * @param owner 提供原账户结算和完成交接的运行时
     * @param identityRegistry 结果和变化编码使用的产品身份表
     * @param commandChanges 本命令原有变化缓冲，由 Lane 写入再交 Owner 收取
     */
    public LaneCancelEvent prepare(long sequence, long ownerUserId, long targetOrderId,
                            long timestamp, long clusterPosition, int ownerLaneId,
                            TradingRuntimeState owner,
                            RuntimeIdentityRegistry identityRegistry,
                            MatcherSettlementChanges commandChanges) {
        if (sequence <= 0 || ownerUserId <= 0 || targetOrderId <= 0 || timestamp < 0 || clusterPosition < 0
                || ownerLaneId < 0 || owner == null || commandChanges == null || coreSequence != 0 || completed) {
            throw new IllegalStateException("invalid Account Lane cancel event");
        }
        if (orderIds == null) orderIds = new long[1];
        orderIds[0] = targetOrderId;
        orderCount = 1;
        return prepare(sequence, ownerUserId, orderIds, orderCount, timestamp, clusterPosition,
                ownerLaneId, true, owner, identityRegistry, commandChanges);
    }

    /**
     * 要求事件尚未使用，复制有效订单编号前缀，固定用户、Lane、提交时间位置和原变化缓冲，并登记预期结算完成位。
     * @param sequence 本次事件归属的正数 Core 命令序号
     * @param ownerUserId 待撤订单共同所属用户的正数编号
     * @param targetOrderIds 多笔撤单的输入编号数组，准备时复制其有效前缀
     * @param targetOrderCount 输入数组内本次有效编号数量
     * @param timestamp 写入撤单终态的非负业务时间
     * @param clusterPosition 写入撤单终态的非负集群日志位置
     * @param ownerLaneId 本次用户所属账户 Lane 编号
     * @param commitAccountLane 是否由此事件自行推进 Lane 提交，批次统一提交时为 false
     * @param owner 提供原账户结算和完成交接的运行时
     * @param identityRegistry 结果和变化编码使用的产品身份表
     * @param commandChanges 本命令原有变化缓冲，由 Lane 写入再交 Owner 收取
     */
    public LaneCancelEvent prepare(long sequence, long ownerUserId, long[] targetOrderIds, int targetOrderCount,
                            long timestamp, long clusterPosition, int ownerLaneId,
                            boolean commitAccountLane,
                            TradingRuntimeState owner,
                            RuntimeIdentityRegistry identityRegistry,
                            MatcherSettlementChanges commandChanges) {
        if (sequence <= 0 || ownerUserId <= 0 || targetOrderIds == null
                || targetOrderCount <= 0 || targetOrderCount > targetOrderIds.length
                || timestamp < 0 || clusterPosition < 0 || ownerLaneId < 0 || owner == null
                || commandChanges == null || coreSequence != 0 || completed) {
            throw new IllegalStateException("invalid Account Lane cancel event");
        }
        if (orderIds == null || orderIds.length < targetOrderCount) {
            orderIds = new long[targetOrderCount];
        }
        System.arraycopy(targetOrderIds, 0, orderIds, 0, targetOrderCount);
        coreSequence = sequence;
        userId = ownerUserId;
        orderCount = targetOrderCount;
        commitTimestamp = timestamp;
        commitClusterPosition = clusterPosition;
        laneId = ownerLaneId;
        commitLane = commitAccountLane;
        runtime = owner;
        identities = identityRegistry;
        changes = commandChanges;
        runtime.expectMatcherSettlement(1L << laneId);
        return this;
    }

    /**
     * 在目标 Lane 进入原结算范围逐笔取消；顺序现货改单已经释放原单时只核对零剩余冻结并补终态元数据。随后捕获变化并检查资金守恒，按配置提交 Lane，最后用提前捕获参数发布完成通知。
     * @param lane 实际执行此事件的账户 Lane，必须与固定路由编号一致
     */
    public void execute(AccountLaneState lane) {
        if (lane.laneId() != laneId) throw new IllegalStateException("cancel reached the wrong Account Lane");
        runtime.enterMatcherSettlementScope(lane, changes);
        try {
            for (/* 当前处理的有效撤单数组下标，不能读取容量之外的旧项。 */ int index = 0; index < orderCount; index++) {
                // 本轮要取消或补终态元数据的订单编号。
                long orderId = orderIds[index];
                // 当前 Lane 中的订单，顺序现货改单可能已经把它标为 CANCELED。
                OrderRuntime order = lane.order(orderId);
                // 顺序现货改单已在预留任务释放原单；本批次撤单事件仍负责该终态的提交元数据与退休交接。
                if (!commitLane && order != null && order.productLine() == com.surprising.product.api.ProductLine.SPOT
                        && order.userId() == userId && order.status() == CoreOrderStatus.CANCELED) {
                    // 已取消现货原单的冻结记录，必须存在且剩余额度为零，避免重复释放资金。
                    ReservationRuntime reservation = lane.reservation(orderId);
                    if (reservation == null || reservation.reservedUnits() != 0) {
                        throw new IllegalStateException("canceled spot batch order retained locked funds");
                    }
                    // 即使时间和日志位置相同，也把该终态纳入本事件的变化捕获。
                    runtime.updateOrderStatusInLane(lane, orderId, order.status(), order.revision(),
                            commitTimestamp, commitClusterPosition);
                } else {
                    runtime.cancelOrderInLane(lane, userId, orderId, commitTimestamp, commitClusterPosition);
                }
                // 两条分支均已在构造终态时写入本次提交元数据，无需再查订单补写。
            }
            changes.prepareLaneTerminal(laneId, identities, lane, runtime, coreSequence);
            changes.laneFundsDelta(laneId).requireConserved(false);
            if (commitLane) {
                if (resultTarget != null) LaneOrderResultTarget.capture(resultTarget, changes.laneDelta(laneId), identities, lane);
                lane.applied(coreSequence);
                lane.committed(coreSequence);
                runtime.publishLaneCommittedSequence(lane);
            }
        } finally {
            runtime.exitMatcherSettlementScope(lane, changes);
        }
        lane.recordSettlementOperation();
        // 完成位发布前取得的运行时通知目标，发布后不再读可回收事件字段。
        TradingRuntimeState completionRuntime = runtime;
        // 提前捕获的目标 Lane 编号，完成通知沿用原路由。
        int completionLaneId = laneId;
        // 提前捕获的本次 Core 序号，防止事件复用后误通知下一命令。
        long completionSequence = coreSequence;
        completed = true;
        completionRuntime.publishMatcherSettlementReady(completionLaneId, completionSequence);
    }

    /**
     * 返回是否由本事件自行推进账户 Lane 的应用和提交水位。
     */
    public boolean commitsLane() { return commitLane; }
    /**
     * 返回当前编号数组的第一笔订单，调用方先确保事件已准备且有效项数非零。
     */
    public long orderId() { return orderIds[0]; }
    /**
     * 返回唯一目标账户 Lane 的位标记，供原完成条件核对。
     */
    public long requiredLaneMask() { return 1L << laneId; }
    /**
     * 返回 Lane 是否已经发布撤单事件完成，不把准备或投递成功当完成。
     */
    public boolean complete() { return completed; }

    /**
     * 完成后一次性交出原变化缓冲并清空事件引用，未完成或重复领取都报错。
     */
    public MatcherSettlementChanges takeChanges() {
        if (!complete() || changes == null) throw new IllegalStateException("cancel event is incomplete");
        // 本次交给 Owner 的原变化缓冲，事件不再保留该引用。
        MatcherSettlementChanges result = changes;
        changes = null;
        return result;
    }

    /**
     * 要求完成且变化缓冲已收取，清理本次引用和标记，保留编号数组存储供后续复用。
     */
    public void clear() {
        if (!complete() || changes != null) throw new IllegalStateException("cancel event was not collected");
        resultTarget = null;
        runtime = null;
        identities = null;
        coreSequence = 0;
        userId = 0;
        orderCount = 0;
        commitTimestamp = 0;
        commitClusterPosition = 0;
        laneId = 0;
        commitLane = false;
        completed = false;
    }

    /**
     * 中止已准备但尚未完成的事件，释放原结算预期并清理引用；调用方须确保 Lane 不再使用它，已完成事件必须先收取结果。
     */
    public void discard() {
        if (complete()) throw new IllegalStateException("completed cancel event must be collected");
        runtime.releaseSettlementExpectation(laneId);
        changes = null;
        resultTarget = null;
        runtime = null;
        identities = null;
        coreSequence = 0;
        userId = 0;
        orderCount = 0;
        commitTimestamp = 0;
        commitClusterPosition = 0;
        laneId = 0;
        commitLane = false;
    }

}
