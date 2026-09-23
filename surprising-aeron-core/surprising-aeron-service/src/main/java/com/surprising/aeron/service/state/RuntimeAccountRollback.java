package com.surprising.aeron.service.state;

import com.surprising.aeron.service.state.account.BalanceRuntime;
import com.surprising.aeron.service.state.account.UserRuntime;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;

/** Account Lane before-images and their two-phase rollback restoration. */
final class RuntimeAccountRollback {
    private final TradingRuntimeState state;
    final LaneLongCaptures<UserRuntime>[] patchUsersBeforeByLane;
    final LaneBalancePatches[] patchBalancesBeforeByLane;
    final LaneLongCaptures<PatchReservationBefore>[] patchReservationsBeforeByLane;
    final LaneLongCaptures<PatchOrderBefore>[] patchOrdersBeforeByLane;
    final LaneLongCaptures<PositionRuntime>[] patchPositionsBeforeByLane;
    final LaneClientOrderCaptures[] patchClientOrdersBeforeByLane;

    @SuppressWarnings("unchecked")
    RuntimeAccountRollback(TradingRuntimeState state, int laneCount) {
        this.state = java.util.Objects.requireNonNull(state);
        patchUsersBeforeByLane = (LaneLongCaptures<UserRuntime>[]) new LaneLongCaptures<?>[laneCount];
        patchBalancesBeforeByLane = new LaneBalancePatches[laneCount];
        patchReservationsBeforeByLane = (LaneLongCaptures<PatchReservationBefore>[]) new LaneLongCaptures<?>[laneCount];
        patchOrdersBeforeByLane = (LaneLongCaptures<PatchOrderBefore>[]) new LaneLongCaptures<?>[laneCount];
        patchPositionsBeforeByLane = (LaneLongCaptures<PositionRuntime>[]) new LaneLongCaptures<?>[laneCount];
        patchClientOrdersBeforeByLane = new LaneClientOrderCaptures[laneCount];
        for (int laneId = 0; laneId < laneCount; laneId++) {
            patchUsersBeforeByLane[laneId] = new LaneLongCaptures<>();
            patchBalancesBeforeByLane[laneId] = new LaneBalancePatches();
            patchReservationsBeforeByLane[laneId] = new LaneLongCaptures<>();
            patchOrdersBeforeByLane[laneId] = new LaneLongCaptures<>();
            patchPositionsBeforeByLane[laneId] = new LaneLongCaptures<>();
            patchClientOrdersBeforeByLane[laneId] = new LaneClientOrderCaptures();
        }
    }

    private static final PatchOrderBefore ABSENT_ORDER_BEFORE = new PatchOrderBefore(null, false);
    private static final PatchReservationBefore ABSENT_RESERVATION_BEFORE = new PatchReservationBefore(null, false);

    void captureUserBefore(long userId) {
        if (state.matcherSettlementChangesScope.get() != null) return;
        LaneLongCaptures<UserRuntime> captured =
                patchUsersBeforeByLane[state.topology.accountLaneId(userId)];
        if (!captured.containsKey(userId)) captured.put(userId, state.user(userId));
    }

    /**
     * Capture a pipelined batch's before-image in one owner-side pass.  Admission has already
     * created the new order and reservation on the Lane, so every order/reservation identity is
     * known to be absent from the owner view.  Avoiding one global lookup plus a cross-Lane capture
     * scan per item removes a quadratic hot path for batch20 while preserving the same contains-key
     * markers used by rollback.
     */
    void captureBatchAdmissionBefore(long userId, OrderRuntime[] admittedOrders,
                                             int itemCount, int laneId) {
        if (userId <= 0 || admittedOrders == null || itemCount <= 0 || itemCount > admittedOrders.length
                || laneId < 0 || laneId >= patchOrdersBeforeByLane.length) {
            throw new IllegalArgumentException("invalid batch admission before-image");
        }
        captureUserBefore(userId);
        LaneLongCaptures<PatchOrderBefore> orderCaptures = patchOrdersBeforeByLane[laneId];
        LaneLongCaptures<PatchReservationBefore> reservationCaptures = patchReservationsBeforeByLane[laneId];
        for (int index = 0; index < itemCount; index++) {
            OrderRuntime admitted = admittedOrders[index];
            if (admitted == null || admitted.userId() != userId) {
                throw new IllegalStateException("batch admission order owner mismatch");
            }
            long orderId = admitted.orderId();
            if (!orderCaptures.containsKey(orderId)) orderCaptures.put(orderId, ABSENT_ORDER_BEFORE);
            if (!reservationCaptures.containsKey(orderId)) {
                reservationCaptures.put(orderId, ABSENT_RESERVATION_BEFORE);
            }
        }
    }

    /** Restore only this Lane's account data; no Owner index or revision is changed here. */
    void restoreLane(AccountLaneState lane) {
        lane.assertOwner();
        int laneId = lane.laneId();
        LaneClientOrderCaptures clients = patchClientOrdersBeforeByLane[laneId];
        for (int index = 0; index < clients.size(); index++) {
            long userId = clients.userId(index);
            long clientKey = clients.clientKey(index);
            TradingRuntimeState.removeClientOrderIndex(lane, userId, clientKey);
            Long before = clients.beforeOrderId(index);
            if (before != null) TradingRuntimeState.putClientOrderIndex(lane, userId, clientKey, before);
        }
        LaneLongCaptures<PatchOrderBefore> orders = patchOrdersBeforeByLane[laneId];
        for (int index = 0; index < orders.size(); index++) {
            lane.removeOrder(orders.key(index));
            OrderRuntime before = orders.value(index).value();
            if (before != null) lane.putOrder(before);
        }
        LaneLongCaptures<PatchReservationBefore> reservations = patchReservationsBeforeByLane[laneId];
        for (int index = 0; index < reservations.size(); index++) {
            long orderId = reservations.key(index);
            PatchReservationBefore captured = reservations.value(index);
            if (captured.pending())
                throw new IllegalStateException("order batch overlapped an existing pending reservation");
            ReservationRuntime current = lane.reservations.remove(orderId);
            if (current != null) {
                TradingRuntimeState.removeUserEntityKeepingContainer(
                        lane.reservationIdsByUser, current.userId(), orderId);
            }
            ReservationRuntime before = captured.value();
            if (before != null) {
                lane.reservations.put(orderId, before.laneValue());
                TradingRuntimeState.addUserEntity(lane.reservationIdsByUser, before.userId(), orderId);
            }
        }
        LaneLongCaptures<PositionRuntime> positions = patchPositionsBeforeByLane[laneId];
        for (int index = 0; index < positions.size(); index++) {
            long positionKey = positions.key(index);
            PositionRuntime current = lane.positions.remove(positionKey);
            if (current != null) TradingRuntimeState.unindexPosition(lane, positionKey, current);
            PositionRuntime before = positions.value(index);
            if (before != null) {
                lane.positions.put(positionKey, before.laneValue());
                TradingRuntimeState.indexPosition(lane, positionKey, before);
            }
        }
        LaneBalancePatches balances = patchBalancesBeforeByLane[laneId];
        for (int index = 0; index < balances.size(); index++) {
            long userId = balances.userId(index);
            int assetId = balances.assetId(index);
            BalanceState before = balances.before(index);
            IntObjectHashMap<BalanceRuntime> userBalances = lane.balances.get(userId);
            if (before == null) {
                if (userBalances != null) {
                    userBalances.remove(assetId);
                    if (userBalances.isEmpty()) lane.balances.remove(userId);
                }
            } else {
                if (userBalances == null) {
                    userBalances = new IntObjectHashMap<>();
                    lane.balances.put(userId, userBalances);
                }
                userBalances.put(assetId, new BalanceRuntime(userId, assetId,
                        before.availableUnits(), before.lockedUnits()));
            }
        }
        LaneLongCaptures<UserRuntime> users = patchUsersBeforeByLane[laneId];
        for (int index = 0; index < users.size(); index++) {
            long userId = users.key(index);
            UserRuntime before = users.value(index);
            if (before == null) {
                lane.users.remove(userId);
                lane.removeUser(userId);
                lane.clientOrderIndex.remove(userId);
                lane.reservationIdsByUser.remove(userId);
                lane.activeOrderIdsByUser.remove(userId);
            } else {
                lane.users.put(userId, before);
                lane.registerUser(userId);
            }
        }
    }

    /** Restore published references only after every Lane has restored its own account state. */
    void restoreOwner() {
        state.assertOwner();
        for (LaneLongCaptures<PatchOrderBefore> orders : patchOrdersBeforeByLane)
            for (int index = 0; index < orders.size(); index++)
                state.publishOrder(orders.key(index), orders.value(index).value());
        for (LaneLongCaptures<PatchReservationBefore> reservations : patchReservationsBeforeByLane)
            for (int index = 0; index < reservations.size(); index++)
                state.publishReservation(reservations.key(index), reservations.value(index).value());
        for (LaneLongCaptures<PositionRuntime> positions : patchPositionsBeforeByLane)
            for (int index = 0; index < positions.size(); index++)
                state.publishPosition(positions.key(index), positions.value(index));
        for (LaneLongCaptures<UserRuntime> users : patchUsersBeforeByLane)
            for (int index = 0; index < users.size(); index++)
                state.publishUser(users.key(index), users.value(index));
    }

    void captureBalanceBefore(long userId, int assetId) {
        MatcherSettlementChanges changes = state.matcherSettlementChangesScope.get();
        LaneBalancePatches captured = changes == null
                ? patchBalancesBeforeByLane[state.topology.accountLaneId(userId)]
                : changes.balancePatches[state.topology.accountLaneId(userId)];
        if (captured.contains(userId, assetId)) return;
        AccountLaneState scoped = state.laneCommandScope.get();
        if (scoped != null) {
            captureBalanceBefore(scoped, captured, userId, assetId);
            return;
        }
        state.onLane(userId, lane -> {
            captureBalanceBefore(lane, captured, userId, assetId);
            return null;
        });
    }

    static void captureBalanceBefore(AccountLaneState lane, LaneBalancePatches captured,
                                             long userId, int assetId) {
        IntObjectHashMap<BalanceRuntime> balances = lane.balances.get(userId);
        BalanceRuntime balance = balances == null ? null : balances.get(assetId);
        captured.add(userId, assetId, balance, lane.pendingReservedUnits(userId, assetId));
    }

    void captureBalanceAfter(AccountLaneState lane, long userId, int assetId) {
        MatcherSettlementChanges changes = state.matcherSettlementChangesScope.get();
        LaneBalancePatches captured = changes == null
                ? patchBalancesBeforeByLane[lane.laneId()]
                : changes.balancePatches[lane.laneId()];
        IntObjectHashMap<BalanceRuntime> balances = lane.balances.get(userId);
        BalanceRuntime balance = balances == null ? null : balances.get(assetId);
        captured.after(userId, assetId, balance, lane.pendingReservedUnits(userId, assetId));
    }

    void captureOrderBefore(long orderId) {
        if (state.matcherSettlementChangesScope.get() != null) return;
        if (capturedOrderBefore(orderId) != null) return;
        OrderRuntime value = state.order(orderId);
        int laneId = captureLane(orderId, value == null ? 0 : value.userId());
        LaneLongCaptures<PatchOrderBefore> captured = patchOrdersBeforeByLane[laneId];
        if (!captured.containsKey(orderId)) {
            captured.put(orderId, value == null ? ABSENT_ORDER_BEFORE
                    : new PatchOrderBefore(value, state.pendingReservations.pendingReservation(orderId, value.userId())));
        }
    }

    void captureReservationBefore(long orderId) {
        if (state.matcherSettlementChangesScope.get() != null) return;
        if (capturedReservationBefore(orderId) != null) return;
        ReservationRuntime value = state.reservation(orderId);
        int laneId = captureLane(orderId, value == null ? 0 : value.userId());
        LaneLongCaptures<PatchReservationBefore> captured = patchReservationsBeforeByLane[laneId];
        if (!captured.containsKey(orderId)) {
            captured.put(orderId, value == null ? ABSENT_RESERVATION_BEFORE
                    : new PatchReservationBefore(value, state.pendingReservations.pendingReservation(orderId, value.userId())));
        }
    }

    int captureLane(long entityId, long userId) {
        AccountLaneState scoped = state.laneCommandScope.get();
        if (scoped != null) return scoped.laneId();
        if (userId > 0) return state.topology.accountLaneId(userId);
        return Math.floorMod(Long.hashCode(entityId), state.accountLanes.length);
    }

    PatchOrderBefore capturedOrderBefore(long orderId) {
        for (LaneLongCaptures<PatchOrderBefore> captured : patchOrdersBeforeByLane) {
            PatchOrderBefore value = captured.get(orderId);
            if (value != null || captured.containsKey(orderId)) return value;
        }
        return null;
    }

    PatchReservationBefore capturedReservationBefore(long orderId) {
        for (LaneLongCaptures<PatchReservationBefore> captured : patchReservationsBeforeByLane) {
            PatchReservationBefore value = captured.get(orderId);
            if (value != null || captured.containsKey(orderId)) return value;
        }
        return null;
    }

    void capturePositionBefore(long positionKey) {
        capturePositionBefore(positionKey, 0);
    }

    void capturePositionBefore(long positionKey, long fallbackUserId) {
        if (state.matcherSettlementChangesScope.get() != null) return;
        PositionRuntime before = state.position(positionKey);
        long userId = before == null ? fallbackUserId : before.userId();
        if (userId > 0) {
            LaneLongCaptures<PositionRuntime> captured =
                    patchPositionsBeforeByLane[state.topology.accountLaneId(userId)];
            if (!captured.containsKey(positionKey)) captured.put(positionKey, before);
        }
    }

    void captureClientOrderBefore(long userId, long clientKey) {
        if (state.matcherSettlementChangesScope.get() != null) return;
        LaneClientOrderCaptures captured = patchClientOrdersBeforeByLane[state.topology.accountLaneId(userId)];
        if (!captured.contains(userId, clientKey)) {
            captured.add(userId, clientKey, state.orderIdByClient(userId, clientKey));
        }
    }

    boolean hasCaptured() {
        for (LaneLongCaptures<UserRuntime> captured : patchUsersBeforeByLane)
            if (!captured.isEmpty()) return true;
        for (LaneBalancePatches captured : patchBalancesBeforeByLane)
            if (captured.size() != 0) return true;
        for (LaneLongCaptures<PatchReservationBefore> captured : patchReservationsBeforeByLane)
            if (!captured.isEmpty()) return true;
        for (LaneLongCaptures<PatchOrderBefore> captured : patchOrdersBeforeByLane)
            if (!captured.isEmpty()) return true;
        for (LaneClientOrderCaptures captured : patchClientOrdersBeforeByLane)
            if (captured.size() != 0) return true;
        return false;
    }

    void clearNonBalanceImages() {
        for (int laneId = 0; laneId < patchUsersBeforeByLane.length; laneId++) {
            patchUsersBeforeByLane[laneId].clear();
            patchReservationsBeforeByLane[laneId].clear();
            patchOrdersBeforeByLane[laneId].clear();
            patchPositionsBeforeByLane[laneId].clear();
            patchClientOrdersBeforeByLane[laneId].clear();
        }
    }

    void publishAndClearBalances() {
        for (LaneBalancePatches captured : patchBalancesBeforeByLane) {
            for (int index = 0; index < captured.size(); index++)
                captured.publishAvailableAt(state, index);
            captured.clear();
        }
    }

    PositionRuntime currentPositionBefore(long positionKey) {
        state.assertOwner();
        PositionRuntime current = state.position(positionKey);
        if (current != null) {
            int laneId = state.topology.accountLaneId(current.userId());
            LaneLongCaptures<PositionRuntime> captured = patchPositionsBeforeByLane[laneId];
            return captured.containsKey(positionKey) ? captured.get(positionKey) : current;
        }
        for (LaneLongCaptures<PositionRuntime> captured : patchPositionsBeforeByLane)
            if (captured.containsKey(positionKey)) return captured.get(positionKey);
        return null;
    }

    OrderRuntime currentOrderBefore(long orderId) {
        state.assertOwner();
        PatchOrderBefore captured = capturedOrderBefore(orderId);
        return captured == null ? state.order(orderId) : captured.value();
    }

    record PatchOrderBefore(OrderRuntime value, boolean pending) {}
    record PatchReservationBefore(ReservationRuntime value, boolean pending) {}
}
