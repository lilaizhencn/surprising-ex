package com.surprising.aeron.service.state.settlement;

import com.surprising.aeron.protocol.CoreTriggerOrderStatus;
import com.surprising.aeron.service.state.TradingRuntimeState;
import com.surprising.aeron.service.state.account.AccountLaneState;
import com.surprising.aeron.service.state.account.LaneBalancePatches;
import com.surprising.aeron.service.state.change.LaneCommitDelta;
import com.surprising.aeron.service.state.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.state.ledger.RuntimeFundsAccumulator;
import com.surprising.aeron.service.state.model.CoreOrderStatus;
import com.surprising.aeron.service.state.model.CoreTriggerOrderState;
import com.surprising.aeron.service.state.order.OrderRuntime;
import com.surprising.aeron.service.state.order.ReservationRuntime;
import com.surprising.aeron.service.state.order.RuntimeAlgoOrderStateTransitions;
import com.surprising.aeron.service.state.order.RuntimeTriggerOrderStateTransitions;
import com.surprising.aeron.service.state.position.PositionRuntime;
import org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;

/**
 * 下单步骤 4.4 与 4.6 共用的结算变化缓冲：各 Lane 写自己的结果，完成交接后供账本核对和 Owner 收集。
 *
 * <p>laneDeltas 保存实体及终态身份，balancePatches 保存余额前后值，laneFundsDeltas
 * 保存由余额变化准备的资金增量，userRevisionDeltas 合并本代用户修订次数。
 * 这些是当前结算的变化与交接数据，权威账户仍在 AccountLaneState。</p>
 *
 * <p>Matcher 确认实际参与账户后准备所需容量；各 Lane 通过 prepareLaneTerminal
 * 整理发布结果并配合事件发布完成位。Owner 收集前不能清空，回收时保留容量供下一代复用。</p>
 */
public final class MatcherSettlementChanges {
    private static final LaneCommitDelta EMPTY_LANE_DELTA = new LaneCommitDelta();
    private static final LaneBalancePatches EMPTY_BALANCE_PATCHES = new LaneBalancePatches();
    private static final RuntimeFundsAccumulator EMPTY_FUNDS_DELTA = new RuntimeFundsAccumulator();
    private static final LongLongHashMap EMPTY_USER_REVISIONS = new LongLongHashMap();
    boolean directPositionIdentities;
    boolean inUse;
    final int ringIndex;
    /** 本代已准备的参与 Lane；实际撮合路由可扩展此集合，回收时据此清理缓冲。 */
    long activeLaneMask;
    /** 各 Lane 输出给 owner 的变化缓冲，在完成交接后消费。 */
    final LaneCommitDelta[] laneDeltas;
    /** 按 Lane 保存的余额前后值，用于资金增量核对。 */
    final LaneBalancePatches[] balancePatches;
    /** 各 Lane 准备的资金增量，交接后供财务核对；未启用账本的路径可由 Owner 收集。 */
    final RuntimeFundsAccumulator[] laneFundsDeltas;
    /** 每个 settlement 内按用户合并的 revision 增量，避免每个 fill 创建 UserRuntime。 */
    final LongLongHashMap[] userRevisionDeltas;

    public MatcherSettlementChanges(int laneCount) {
        this(laneCount, -1);
    }

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

    public void ensureActiveLanes(long laneMask) {
        while (laneMask != 0) {
            int laneId = Long.numberOfTrailingZeros(laneMask);
            laneMask &= laneMask - 1;
            if (laneDeltas[laneId] == EMPTY_LANE_DELTA) laneDeltas[laneId] = new LaneCommitDelta();
            if (balancePatches[laneId] == EMPTY_BALANCE_PATCHES) balancePatches[laneId] = new LaneBalancePatches();
            if (laneFundsDeltas[laneId] == EMPTY_FUNDS_DELTA) laneFundsDeltas[laneId] = new RuntimeFundsAccumulator();
            if (userRevisionDeltas[laneId] == EMPTY_USER_REVISIONS) userRevisionDeltas[laneId] = new LongLongHashMap();
        }
    }

    public void addUserRevision(int laneId, long userId, long count) {
        userRevisionDeltas[laneId].addToValue(userId, count);
    }

    void applyUserRevisions(int laneId, AccountLaneState lane) {
        LongLongHashMap deltas = userRevisionDeltas[laneId];
        if (deltas.isEmpty()) return;
        lane.applyUserRevisions(deltas, laneDeltas[laneId]);
        deltas.clear();
    }

    public void ensureAdmissionCapacity(int laneId, int expectedOrders) {
        if (expectedOrders <= 0) return;
        ensureActiveLanes(1L << laneId);
        laneDeltas[laneId].ensureAdmissionCapacity(expectedOrders);
        laneDeltas[laneId].ensureTerminalCapacity(expectedOrders);
    }

    public void ensureOrderCapacity(int expectedOrders, long laneMask) {
        if (expectedOrders <= 0) return;
        activeLaneMask |= laneMask;
        ensureActiveLanes(laneMask);
        while (laneMask != 0) {
            int laneId = Long.numberOfTrailingZeros(laneMask);
            laneMask &= laneMask - 1;
            laneDeltas[laneId].ensureOrderCapacity(expectedOrders);
            laneDeltas[laneId].ensureTerminalCapacity(expectedOrders);
        }
    }

    public void prepareLaneTerminal(int laneId, RuntimeIdentityRegistry identities, AccountLaneState lane, TradingRuntimeState runtime, long sequence) {
        applyUserRevisions(laneId, lane);
        LaneCommitDelta changes = laneDeltas[laneId];
        // The Owner applies and commits these changes serially. Keep terminal identity
        // receipts for the bounded tombstone window; order and reservation counters
        // already live in the authoritative runtime values.
        for (int index = 0; index < changes.orders().size(); index++) {
            long orderId = changes.orders().keyAt(index);
            OrderRuntime order = changes.orders().valueAt(index);
            if (order == null || !order.status().terminal()) continue;
            changes.recordTerminalOrder(order);
            ReservationRuntime reservation = lane.reservation(orderId);
            if (reservation != null && reservation.reservedUnits() != 0) continue;
            if (reservation != null) {
                lane.removeReservationValue(orderId);
                lane.removeReservationId(order.userId(), orderId);
                changes.removeReservationRoute(orderId);
            }
            // 原生拒单保留已有的 REJECTED 查询记录，但不再持有冻结或 reservation。
            if (order.status() == CoreOrderStatus.REJECTED) continue;
            RuntimeAlgoOrderStateTransitions.retireChild(lane, identities, order, changes);
            OrderRuntime removed = lane.removeOrder(orderId);
            if (removed != null) {
                changes.removeOrderRoute(orderId);
                if (removed.retireForReuse(sequence)) changes.retiredOrder(removed);
            }
            lane.releaseOrderClientIdentities(identities, orderId, order.userId());
            lane.removeClientOrdersForOrder(order.userId(), orderId);
        }
        for (int positionIndex = 0; positionIndex < changes.positions().size(); positionIndex++) {
            PositionRuntime position = changes.positions().valueAt(positionIndex);
            if (position != null && position.signedQuantitySteps() == 0) {
                LongHashSet ids = lane.triggerIds(position.userId());
                if (ids != null && !ids.isEmpty()) {
                    String instrumentId = identities.instrumentId(position.symbolId());
                    var iterator = ids.longIterator();
                    while (iterator.hasNext()) {
                        long id = iterator.next();
                        CoreTriggerOrderState trigger = lane.triggerOrder(id);
                        if (trigger == null || trigger.status() != CoreTriggerOrderStatus.PENDING
                                || trigger.positionSide() != position.positionSide()
                                || trigger.marginMode() != position.marginMode()
                                || !instrumentId.equals(trigger.instrumentId())) continue;
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
        TradingRuntimeState.prepareBalanceFundsDelta(balancePatches[laneId], laneFundsDeltas[laneId]);
    }

    public void prepareAdmissionLane(int laneId, TradingRuntimeState runtime) {
        TradingRuntimeState.prepareBalanceFundsDelta(balancePatches[laneId], laneFundsDeltas[laneId]);
    }

    public void copyBalanceBeforeTo(LaneBalancePatches target) {
        if (target == null) throw new IllegalArgumentException("balance capture target is required");
        for (int laneId = 0; laneId < balancePatches.length; laneId++) {
            LaneBalancePatches source = balancePatches[laneId];
            if (source == EMPTY_BALANCE_PATCHES) continue;
            target.mergeBefore(source);
        }
    }

    public void appendFundsDelta(long laneMask, RuntimeFundsAccumulator target) {
        if (target == null) throw new IllegalArgumentException("funds accumulator is required");
        long lanes = laneMask;
        while (lanes != 0) {
            int laneId = Long.numberOfTrailingZeros(lanes);
            lanes &= lanes - 1;
            target.add(laneFundsDeltas[laneId]);
        }
    }

    public void returnRetiredOrders(TradingRuntimeState runtime) {
        long lanes = activeLaneMask;
        while (lanes != 0) {
            int laneId = Long.numberOfTrailingZeros(lanes); lanes &= lanes - 1;
            laneDeltas[laneId].returnRetiredOrders(runtime.accountLane(laneId));
        }
    }

    public void clear() {
        directPositionIdentities = false;
        long lanes = activeLaneMask;
        while (lanes != 0) {
            int lane = Long.numberOfTrailingZeros(lanes); lanes &= lanes - 1;
            userRevisionDeltas[lane].clear();
            laneDeltas[lane].clear();
            balancePatches[lane].clear();
            laneFundsDeltas[lane].clear();
        }
        activeLaneMask = 0;
    }

    /** Core 内部业务协作入口；状态仍由原所属线程或有效读取屏障持有。 */
    public long activeLaneMask() { return activeLaneMask; }
    public boolean inUse() { return inUse; }
    public void inUse(boolean value) { this.inUse = value; }
    public void activeLaneMask(long value) { this.activeLaneMask = value; }
    public int ringIndex() { return ringIndex; }
    public boolean directPositionIdentities() { return directPositionIdentities; }

    public LaneCommitDelta laneDelta(int index) { return laneDeltas[index]; }

    public LaneBalancePatches balancePatch(int index) { return balancePatches[index]; }
    public int balancePatchCount() { return balancePatches.length; }
    public RuntimeFundsAccumulator laneFundsDelta(int index) { return laneFundsDeltas[index]; }

}
