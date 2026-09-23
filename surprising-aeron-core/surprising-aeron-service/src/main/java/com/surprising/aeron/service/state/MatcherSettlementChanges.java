package com.surprising.aeron.service.state;

import com.surprising.aeron.service.state.account.UserRuntime;
import com.surprising.aeron.service.state.model.CoreOrderStatus;
import com.surprising.aeron.service.state.model.CoreTriggerOrderState;
import org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;
import com.surprising.aeron.protocol.CoreTriggerOrderStatus;

final class MatcherSettlementChanges {
    private static final LaneCommitDelta EMPTY_LANE_DELTA = new LaneCommitDelta();
    private static final LaneBalancePatches EMPTY_BALANCE_PATCHES = new LaneBalancePatches();
    private static final RuntimeFundsAccumulator EMPTY_FUNDS_DELTA = new RuntimeFundsAccumulator();
    private static final LongLongHashMap EMPTY_USER_REVISIONS = new LongLongHashMap();
    boolean directPositionIdentities;
    boolean inUse;
    final int ringIndex;
    /** Owner派发前固定的参与Lane；事件完成后仅回收这些Lane的缓冲。 */
    long activeLaneMask;
    /** 各 Lane 输出给 owner 的变化缓冲，在完成交接后消费。 */
    final LaneCommitDelta[] laneDeltas;
    /** 按 Lane 保存的余额前后值，用于资金增量核对。 */
    final LaneBalancePatches[] balancePatches;
    /** 各 Lane 收集的资金增量，完成后由 owner 合并。 */
    final RuntimeFundsAccumulator[] laneFundsDeltas;
    /** 每个 settlement 内按用户合并的 revision 增量，避免每个 fill 创建 UserRuntime。 */
    final LongLongHashMap[] userRevisionDeltas;

    MatcherSettlementChanges(int laneCount) {
        this(laneCount, -1);
    }

    MatcherSettlementChanges(int laneCount, int ringIndex) {
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

    void ensureActiveLanes(long laneMask) {
        while (laneMask != 0) {
            int laneId = Long.numberOfTrailingZeros(laneMask);
            laneMask &= laneMask - 1;
            if (laneDeltas[laneId] == EMPTY_LANE_DELTA) laneDeltas[laneId] = new LaneCommitDelta();
            if (balancePatches[laneId] == EMPTY_BALANCE_PATCHES) balancePatches[laneId] = new LaneBalancePatches();
            if (laneFundsDeltas[laneId] == EMPTY_FUNDS_DELTA) laneFundsDeltas[laneId] = new RuntimeFundsAccumulator();
            if (userRevisionDeltas[laneId] == EMPTY_USER_REVISIONS) userRevisionDeltas[laneId] = new LongLongHashMap();
        }
    }

    /** 每个Lane完成的当前命令预留数量，只在该Lane写入，Owner在事件完成后汇总。 */
    final int[] completedPending = new int[Long.SIZE];

    void addUserRevision(int laneId, long userId, long count) {
        userRevisionDeltas[laneId].addToValue(userId, count);
    }

    void applyUserRevisions(int laneId, AccountLaneState lane) {
        LongLongHashMap deltas = userRevisionDeltas[laneId];
        if (deltas.isEmpty()) return;
        LaneCommitDelta changes = laneDeltas[laneId];
        deltas.forEachKeyValue((userId, count) -> {
            UserRuntime current = lane.users.get(userId);
            if (current == null) {
                throw new IllegalStateException("runtime user disappeared during matcher settlement: " + userId);
            }
            UserRuntime advanced = new UserRuntime(current.productLine(), userId,
                    Math.addExact(current.revision(), count), current.positionMode());
            lane.users.put(userId, advanced);
            changes.putUser(userId, advanced);
        });
        deltas.clear();
    }

    void ensureAdmissionCapacity(int laneId, int expectedOrders) {
        if (expectedOrders <= 0) return;
        ensureActiveLanes(1L << laneId);
        laneDeltas[laneId].ensureAdmissionCapacity(expectedOrders);
        laneDeltas[laneId].ensureTerminalCapacity(expectedOrders);
    }

    void ensureOrderCapacity(int expectedOrders, long laneMask) {
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

    void prepareLaneTerminal(int laneId, RuntimeIdentityRegistry identities, AccountLaneState lane, TradingRuntimeState runtime) {
        applyUserRevisions(laneId, lane);
        LaneCommitDelta changes = laneDeltas[laneId];
        // Freeze only the final value for each changed key. A single settlement can update
        // an order, reservation, or position several times before the Owner sees it.
        changes.orders.capturePublicationValues();
        changes.reservations.capturePublicationValues();
        // Terminal identity is a Lane fact. Capture its primitive fields before the
        // publication handoff so the Owner never has to walk changed orders again merely
        // to update the bounded tombstone window.
        changes.orders.forEach((orderId, order) -> {
            if (order == null || !order.status().terminal()) return;
            changes.recordTerminalOrder(order);
            ReservationRuntime reservation = lane.reservations.get(orderId);
            if (reservation != null && reservation.reservedUnits() != 0) return;
            if (reservation != null) {
                lane.reservations.remove(orderId);
                TradingRuntimeState.removeUserEntityKeepingContainer(lane.reservationIdsByUser, order.userId(), orderId);
                changes.removeReservationRoute(orderId);
            }
            // 原生拒单保留已有的 REJECTED 查询记录，但不再持有冻结或 reservation。
            if (order.status() == CoreOrderStatus.REJECTED) return;
            lane.removeOrder(orderId);
            changes.removeOrderRoute(orderId);
            lane.clientKeysByOrderId.forEach(orderId,
                    clientKey -> identities.releaseClientKeyInLane(lane, order.userId(), clientKey));
            TradingRuntimeState.removeClientOrdersForOrder(lane, order.userId(), orderId);
        });
        changes.positions.forEach((positionKey, position) -> {
            if (position != null && position.signedQuantitySteps() == 0) {
                LongHashSet ids = lane.cold.triggerIdsByUser.get(position.userId());
                if (ids != null && !ids.isEmpty()) {
                    String symbol = identities.symbol(position.symbolId());
                    var iterator = ids.longIterator();
                    while (iterator.hasNext()) {
                        long id = iterator.next();
                        CoreTriggerOrderState trigger = lane.cold.triggerOrders.get(id);
                        if (trigger == null || trigger.status() != CoreTriggerOrderStatus.PENDING
                                || trigger.positionSide() != position.positionSide()
                                || trigger.marginMode() != position.marginMode()
                                || !symbol.equals(trigger.symbol())) continue;
                        CoreTriggerOrderState canceled = RuntimeTriggerOrderStateTransitions.preparePendingCancellation(trigger);
                        lane.putTrigger(canceled);
                        changes.putTrigger(id, canceled);
                        changes.closedTriggerCount = Math.incrementExact(changes.closedTriggerCount);
                    }
                }
            }
            changes.positions.putPrepared(positionKey,
                    position == null ? null : RuntimePositionIndexValue.from(position, identities));
        });
        changes.preparePublication();
        TradingRuntimeState.prepareBalanceFundsDelta(balancePatches[laneId], laneFundsDeltas[laneId]);
    }

    void prepareAdmissionLane(int laneId, TradingRuntimeState runtime) {
        TradingRuntimeState.prepareBalanceFundsDelta(balancePatches[laneId], laneFundsDeltas[laneId]);
    }

    void copyBalanceBeforeTo(LaneBalancePatches target) {
        if (target == null) throw new IllegalArgumentException("balance capture target is required");
        for (int laneId = 0; laneId < balancePatches.length; laneId++) {
            LaneBalancePatches source = balancePatches[laneId];
            if (source == EMPTY_BALANCE_PATCHES) continue;
            target.mergeBefore(source);
        }
    }

    void appendFundsDelta(long laneMask, RuntimeFundsAccumulator target) {
        if (target == null) throw new IllegalArgumentException("funds accumulator is required");
        long lanes = laneMask;
        while (lanes != 0) {
            int laneId = Long.numberOfTrailingZeros(lanes);
            lanes &= lanes - 1;
            target.add(laneFundsDeltas[laneId]);
        }
    }

    void clear() {
        directPositionIdentities = false;
        long lanes = activeLaneMask;
        while (lanes != 0) {
            int lane = Long.numberOfTrailingZeros(lanes); lanes &= lanes - 1;
            completedPending[lane] = 0;
            userRevisionDeltas[lane].clear();
            laneDeltas[lane].clear();
            balancePatches[lane].clear();
            laneFundsDeltas[lane].clear();
        }
        activeLaneMask = 0;
    }
}
