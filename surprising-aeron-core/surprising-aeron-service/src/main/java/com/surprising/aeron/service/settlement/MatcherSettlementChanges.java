package com.surprising.aeron.service.settlement;

import com.surprising.aeron.model.CoreOrderStatus;
import com.surprising.aeron.model.CoreTriggerOrderState;
import com.surprising.aeron.protocol.CoreTriggerOrderStatus;
import com.surprising.aeron.service.account.AccountLaneState;
import com.surprising.aeron.service.account.LaneBalancePatches;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.change.LaneCommitDelta;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.order.state.OrderRuntime;
import com.surprising.aeron.service.order.state.ReservationRuntime;
import com.surprising.aeron.service.order.state.RuntimeTriggerOrderStateTransitions;
import com.surprising.aeron.service.position.PositionRuntime;
import com.surprising.aeron.service.treasury.RuntimeFundsAccumulator;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;

/**
 * 下单步骤 4.4 与 4.6 的结算变化缓冲。每个 Lane 写自己的实体变化、余额前后值、资金增量及用户修订次数；完成交接后供财务核对和 Owner 收集。权威账户仍在 AccountLaneState，缓冲只在当前事件租约内有效，清空保留容量。
 */
public final class MatcherSettlementChanges {
    /** 未激活 Lane 共用的空实体变化哨兵，写入前须替换为独立缓冲。 */
    private static final LaneCommitDelta EMPTY_LANE_DELTA = new LaneCommitDelta();
    /** 未激活 Lane 共用的空余额捕获哨兵，不可写入业务变化。 */
    private static final LaneBalancePatches EMPTY_BALANCE_PATCHES = new LaneBalancePatches();
    /** 未激活 Lane 共用的空资金增量哨兵，不可累加金额。 */
    private static final RuntimeFundsAccumulator EMPTY_FUNDS_DELTA = new RuntimeFundsAccumulator();
    /** 未激活 Lane 共用的空用户修订哨兵，不可写入用户增量。 */
    private static final LongLongHashMap EMPTY_USER_REVISIONS = new LongLongHashMap();
    /**  本代是否直接使用持仓身份交接；Owner 收集时据此释放已发布身份。 返回本代是否采用直达持仓身份交接，供 Owner 选择身份释放方式。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    boolean directPositionIdentities;
    /**
     *  由外部环槽或对象池管理的占用标记，clear 不自行重置。
     * 返回外部池或环槽管理的占用标记。
     * 由池或环槽管理方更新占用标记，不在此等待消费者。
     * @param inUse 本槽是否正在被当前事件占用
     */
    @Accessors(fluent = true, chain = false)
    @Getter
    @Setter
    boolean inUse;
    /**  创建时确定的固定环槽编号，负一表示普通独立缓冲。 返回固定环槽编号，负一表示未关联固定环槽。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    final int ringIndex;
    /**
     *  本代已登记参与的 Lane 位图，路由扩展时补充，清理时只处理这些 Lane。
     * 返回本代登记过的参与 Lane 位图。
     * 由持有租约的调用方直接设置参与位图；这里只保存位图，不分配各 Lane 缓冲。
     * @param activeLaneMask 本代参与账户 Lane 的完整位图
     */
    @Accessors(fluent = true, chain = false)
    @Getter
    @Setter
    long activeLaneMask;
    /** 按 Lane 保存的实体与终态变化列，完成交接前由所属 Lane 独占写入。 */
    final LaneCommitDelta[] laneDeltas;
    /** 按 Lane 保存的余额前后值，供回滚及资金差额核对。 */
    final LaneBalancePatches[] balancePatches;
    /** 由各 Lane 余额前后值准备的资金增量，交接后供账本或未激活路径消费。 */
    final RuntimeFundsAccumulator[] laneFundsDeltas;
    /** 本代每 Lane 按用户聚合的修订次数，收尾应用成功后清空。 */
    final LongLongHashMap[] userRevisionDeltas;

    /**
     * 按账户 Lane 数创建缓冲入口，默认不关联固定环槽。
     * @param laneCount 运行时拓扑中的账户 Lane 数量
     */
    public MatcherSettlementChanges(int laneCount) {
        this(laneCount, -1);
    }

    /**
     * 保存环槽编号并创建各 Lane 的缓冲列，未参与的 Lane 先共用只读空哨兵，真正使用时再分配独立缓冲。
     * @param laneCount 运行时拓扑中的账户 Lane 数量
     * @param ringIndex 固定环槽编号，独立缓冲使用负一
     */
    public MatcherSettlementChanges(int laneCount, int ringIndex) {
        this.ringIndex = ringIndex;
        laneDeltas = new LaneCommitDelta[laneCount];
        balancePatches = new LaneBalancePatches[laneCount];
        laneFundsDeltas = new RuntimeFundsAccumulator[laneCount];
        userRevisionDeltas = new LongLongHashMap[laneCount];
        java.util.Arrays.fill(laneDeltas, EMPTY_LANE_DELTA);
        java.util.Arrays.fill(balancePatches, EMPTY_BALANCE_PATCHES);
        java.util.Arrays.fill(laneFundsDeltas, EMPTY_FUNDS_DELTA);
        java.util.Arrays.fill(userRevisionDeltas, EMPTY_USER_REVISIONS);
    }

    /**
     * 为位图内的 Lane 把空哨兵换成独立可写缓冲；本方法只准备存储，不自行登记 activeLaneMask。
     * @param laneMask 需要准备或读取的账户 Lane 位图
     */
    public void ensureActiveLanes(long laneMask) {
        while (laneMask != 0) {
            // 位图或数组遍历中当前操作的账户 Lane 编号。
            int laneId = Long.numberOfTrailingZeros(laneMask);
            laneMask &= laneMask - 1;
            if (laneDeltas[laneId] == EMPTY_LANE_DELTA) laneDeltas[laneId] = new LaneCommitDelta();
            if (balancePatches[laneId] == EMPTY_BALANCE_PATCHES) balancePatches[laneId] = new LaneBalancePatches();
            if (laneFundsDeltas[laneId] == EMPTY_FUNDS_DELTA) laneFundsDeltas[laneId] = new RuntimeFundsAccumulator();
            if (userRevisionDeltas[laneId] == EMPTY_USER_REVISIONS) userRevisionDeltas[laneId] = new LongLongHashMap();
        }
    }

    /**
     * 把用户在本代结算中的修订增量累加到所属 Lane 缓冲；用户权威修订号在收尾时统一应用。
     * @param laneId 当前操作的账户 Lane 编号
     * @param userId 需要累加修订的用户编号
     * @param count 本次增加的用户修订次数
     */
    public void addUserRevision(int laneId, long userId, long count) {
        userRevisionDeltas[laneId].addToValue(userId, count);
    }

    /**
     * 在所属 Lane 应用已聚合的用户修订增量并写入发布变化；只有正常返回后才清空增量，失败时保留原缓冲。
     * @param laneId 当前操作的账户 Lane 编号
     * @param lane 当前线程持有的对应账户 Lane
     */
    void applyUserRevisions(int laneId, AccountLaneState lane) {
        // 当前 Lane 本代尚未应用的用户修订增量。
        LongLongHashMap deltas = userRevisionDeltas[laneId];
        if (deltas.isEmpty()) return;
        lane.applyUserRevisions(deltas, laneDeltas[laneId]);
        deltas.clear();
    }

    /**
     * 为指定 Lane 提前准备准入与终态槽容量；非正数请求不处理，参与位图仍由调用方登记。
     * @param laneId 当前操作的账户 Lane 编号
     * @param expectedOrders 预计要容纳的订单数量
     */
    public void ensureAdmissionCapacity(int laneId, int expectedOrders) {
        if (expectedOrders <= 0) return;
        ensureActiveLanes(1L << laneId);
        laneDeltas[laneId].ensureAdmissionCapacity(expectedOrders);
        laneDeltas[laneId].ensureTerminalCapacity(expectedOrders);
    }

    /**
     * 按本次实际路由登记参与 Lane，并为这些 Lane 提前准备订单及终态槽容量；非正数请求不处理。
     * @param expectedOrders 预计要容纳的订单数量
     * @param laneMask 需要准备或读取的账户 Lane 位图
     */
    public void ensureOrderCapacity(int expectedOrders, long laneMask) {
        if (expectedOrders <= 0) return;
        activeLaneMask |= laneMask;
        ensureActiveLanes(laneMask);
        while (laneMask != 0) {
            // 位图或数组遍历中当前操作的账户 Lane 编号。
            int laneId = Long.numberOfTrailingZeros(laneMask);
            laneMask &= laneMask - 1;
            laneDeltas[laneId].ensureOrderCapacity(expectedOrders);
            laneDeltas[laneId].ensureTerminalCapacity(expectedOrders);
        }
    }

    /**
     * 下单步骤 4.4 的账户收尾：应用修订、记录终态，预留耗尽后移除可退休订单并释放客户身份；拒单保留查询记录。零持仓同时取消同合约、方向及保证金模式的待触发单，最后准备持仓退休、发布值和余额资金增量。
     * @param laneId 当前操作的账户 Lane 编号
     * @param identities 解析合约名称及释放客户订单身份的运行时字典
     * @param lane 当前线程持有的对应账户 Lane
     * @param sequence 本代 Core 序号，用于设置订单再次复用前必须越过的退休边界
     */
    public void prepareLaneTerminal(int laneId, RuntimeIdentityRegistry identities, AccountLaneState lane, long sequence) {
        applyUserRevisions(laneId, lane);
        // 当前 Lane 的实体、终态及发布变化缓冲。
        LaneCommitDelta changes = laneDeltas[laneId];
        // Owner 收齐回执后按序提交；这里保存终态身份供有限保留窗口使用，
        // 订单和预留金额计数仍以 Lane 中的权威值为准。
        for (/* 当前遍历的订单变化下标。 */ int index = 0; index < changes.orders().size(); index++) {
            // 本项变化的原语订单编号。
            long orderId = changes.orders().keyAt(index);
            // 当前变更槽中的订单值，空值或非终态跳过。
            OrderRuntime order = changes.orders().valueAt(index);
            if (order == null || !order.status().terminal()) continue;
            changes.recordTerminalOrder(order);
            // 本终态订单在权威 Lane 中仍保留的预留，未耗尽时不能移除订单。
            ReservationRuntime reservation = lane.reservation(orderId);
            if (reservation != null && reservation.reservedUnits() != 0) continue;
            if (reservation != null) {
                lane.removeReservationValue(orderId);
                lane.removeReservationId(order.userId(), orderId);
                changes.removeReservationRoute(orderId);
            }
            // 原生拒单保留已有的 REJECTED 查询记录，但不再持有冻结或 reservation。
            if (order.status() == CoreOrderStatus.REJECTED) continue;
            lane.retireAlgoChild(identities, order, changes);
            // 从 Lane 订单表实际移除的权威订单，用于登记延后复用。
            OrderRuntime removed = lane.removeOrder(orderId);
            if (removed != null) {
                changes.removeOrderRoute(orderId);
                if (removed.retireForReuse(sequence)) changes.retiredOrder(removed);
            }
            lane.releaseOrderClientIdentities(identities, orderId, order.userId());
            lane.removeClientOrdersForOrder(order.userId(), orderId);
        }
        for (/* 当前遍历的持仓变化下标。 */ int positionIndex = 0; positionIndex < changes.positions().size(); positionIndex++) {
            // 本项变化后的持仓值，数量归零时需要清理相关待触发单。
            PositionRuntime position = changes.positions().valueAt(positionIndex);
            if (position != null && position.signedQuantitySteps() == 0) {
                // 该持仓用户在本 Lane 的触发单编号集合。
                LongHashSet ids = lane.triggerIds(position.userId());
                if (ids != null && !ids.isEmpty()) {
                    // 零持仓对应的业务合约名称，用于筛选相关触发单。
                    String instrumentId = identities.instrumentId(position.symbolId());
                    // 逐项读取该用户触发单编号的原语迭代器。
                    var iterator = ids.longIterator();
                    while (iterator.hasNext()) {
                        // 当前检查的触发单编号。
                        long id = iterator.next();
                        // 当前编号对应的权威触发单。
                        CoreTriggerOrderState trigger = lane.triggerOrder(id);
                        if (trigger == null || trigger.status() != CoreTriggerOrderStatus.PENDING
                                || trigger.positionSide() != position.positionSide()
                                || trigger.marginMode() != position.marginMode()
                                || !instrumentId.equals(trigger.instrumentId())) continue;
                        // 同合约、方向及保证金模式的待触发单取消值。
                        CoreTriggerOrderState canceled = RuntimeTriggerOrderStateTransitions.preparePendingCancellation(trigger);
                        lane.putTrigger(canceled);
                        changes.putTrigger(id, canceled);
                        changes.closedTriggerCount(Math.incrementExact(changes.closedTriggerCount()));
                    }
                }
            }
        }
        changes.preparePositionRetirement(lane);
        changes.preparePublication();
        TradingAccountRuntime.prepareBalanceFundsDelta(balancePatches[laneId], laneFundsDeltas[laneId]);
    }

    /**
     * 从指定 Lane 已捕获的余额前后值准备准入资金增量，不在这里执行完整订单终态清理。
     * @param laneId 当前操作的账户 Lane 编号
     */
    public void prepareAdmissionLane(int laneId) {
        TradingAccountRuntime.prepareBalanceFundsDelta(balancePatches[laneId], laneFundsDeltas[laneId]);
    }

    /**
     * 遍历全部已经分配的余额缓冲，把前值合并到目标；跳过空哨兵，不只遍历 activeLaneMask。
     * @param target 接收余额原始前值的非空目标缓冲
     */
    public void copyBalanceBeforeTo(LaneBalancePatches target) {
        if (target == null) throw new IllegalArgumentException("balance capture target is required");
        for (/* 位图或数组遍历中当前操作的账户 Lane 编号。 */ int laneId = 0; laneId < balancePatches.length; laneId++) {
            // 已分配的某 Lane 余额前后值缓冲，空哨兵直接跳过。
            LaneBalancePatches source = balancePatches[laneId];
            if (source == EMPTY_BALANCE_PATCHES) continue;
            target.mergeBefore(source);
        }
    }

    /**
     * 按调用方给出的 Lane 位图，把各 Lane 已准备的资金增量加入目标累加器，不清空源。
     * @param laneMask 需要准备或读取的账户 Lane 位图
     * @param target 接收所选 Lane 资金增量的非空累加器
     */
    public void appendFundsDelta(long laneMask, RuntimeFundsAccumulator target) {
        if (target == null) throw new IllegalArgumentException("funds accumulator is required");
        // 本次尚未遍历的 Lane 位图，每次移除最低有效位。
        long lanes = laneMask;
        while (lanes != 0) {
            // 位图或数组遍历中当前操作的账户 Lane 编号。
            int laneId = Long.numberOfTrailingZeros(lanes);
            lanes &= lanes - 1;
            target.add(laneFundsDeltas[laneId]);
        }
    }

    /**
     * 把参与 Lane 的待退休订单交回对应账户的回收缓存；对象能否再次初始化仍受退休序号约束。
     * @param runtime 用于把退休订单交回各账户 Lane 的所属运行时
     */
    public void returnRetiredOrders(TradingAccountRuntime runtime) {
        // 本次尚未遍历的 Lane 位图，每次移除最低有效位。
        long lanes = activeLaneMask;
        while (lanes != 0) {
            // 位图或数组遍历中当前操作的账户 Lane 编号。
            int laneId = Long.numberOfTrailingZeros(lanes); lanes &= lanes - 1;
            laneDeltas[laneId].returnRetiredOrders(runtime.accountLane(laneId));
        }
    }

    /**
     * 清除参与 Lane 的修订、变化、余额与资金缓冲并重置参与位图和持仓身份标记；保留容量，不在此修改 inUse 或环槽编号，也不自动归还退休订单，调用方须先完成退休交接。
     */
    public void clear() {
        directPositionIdentities = false;
        // 本次尚未遍历的 Lane 位图，每次移除最低有效位。
        long lanes = activeLaneMask;
        while (lanes != 0) {
            // 当前需要清空的 Lane 数组下标。
            int lane = Long.numberOfTrailingZeros(lanes); lanes &= lanes - 1;
            userRevisionDeltas[lane].clear();
            laneDeltas[lane].clear();
            balancePatches[lane].clear();
            laneFundsDeltas[lane].clear();
        }
        activeLaneMask = 0;
    }

    /**
     * 借用指定 Lane 的实体与终态变化缓冲，不复制内容。
     * @param index 所借用缓冲对应的 Lane 数组下标
     */
    public LaneCommitDelta laneDelta(int index) { return laneDeltas[index]; }

    /**
     * 借用指定 Lane 的余额前后值缓冲，不复制内容。
     * @param index 所借用缓冲对应的 Lane 数组下标
     */
    public LaneBalancePatches balancePatch(int index) { return balancePatches[index]; }
    /**
     * 返回按拓扑保留的余额缓冲列数，不表示本代活跃 Lane 数。
     */
    public int balancePatchCount() { return balancePatches.length; }
    /**
     * 借用指定 Lane 已准备的资金增量缓冲，不复制内容。
     * @param index 所借用缓冲对应的 Lane 数组下标
     */
    public RuntimeFundsAccumulator laneFundsDelta(int index) { return laneFundsDeltas[index]; }

}
