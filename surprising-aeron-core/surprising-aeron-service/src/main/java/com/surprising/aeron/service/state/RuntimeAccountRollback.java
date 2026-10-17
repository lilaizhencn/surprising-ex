package com.surprising.aeron.service.state;

import com.surprising.aeron.service.state.model.CoreTriggerOrderState;
import com.surprising.aeron.service.state.account.BalanceRuntime;
import com.surprising.aeron.service.state.account.UserRuntime;
import com.surprising.aeron.service.state.risk.RiskSnapshotRuntime;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;

/** Account Lane before-images and their two-phase rollback restoration. */
final class RuntimeAccountRollback {
    private final TradingRuntimeState state;
    final RuntimeUserChangeBuffer[] patchUsersBeforeByLane;
    final LaneBalancePatches[] patchBalancesBeforeByLane;
    final LaneLongCaptures<PatchReservationBefore>[] patchReservationsBeforeByLane;
    final LaneLongCaptures<PatchOrderBefore>[] patchOrdersBeforeByLane;
    final LaneLongCaptures<PositionRuntime>[] patchPositionsBeforeByLane;
    final LaneClientOrderCaptures[] patchClientOrdersBeforeByLane;
    final LaneLongCaptures<CoreTriggerOrderState>[] patchTriggersBeforeByLane;
    final LaneLongCaptures<RiskSnapshotRuntime>[] patchRiskSnapshotsBeforeByLane;

    @SuppressWarnings("unchecked")
    RuntimeAccountRollback(TradingRuntimeState state, int laneCount) {
        this.state = java.util.Objects.requireNonNull(state);
        patchUsersBeforeByLane = new RuntimeUserChangeBuffer[laneCount];
        patchBalancesBeforeByLane = new LaneBalancePatches[laneCount];
        patchReservationsBeforeByLane = (LaneLongCaptures<PatchReservationBefore>[]) new LaneLongCaptures<?>[laneCount];
        patchOrdersBeforeByLane = (LaneLongCaptures<PatchOrderBefore>[]) new LaneLongCaptures<?>[laneCount];
        patchPositionsBeforeByLane = (LaneLongCaptures<PositionRuntime>[]) new LaneLongCaptures<?>[laneCount];
        patchClientOrdersBeforeByLane = new LaneClientOrderCaptures[laneCount];
        patchTriggersBeforeByLane = (LaneLongCaptures<CoreTriggerOrderState>[]) new LaneLongCaptures<?>[laneCount];
        patchRiskSnapshotsBeforeByLane = (LaneLongCaptures<RiskSnapshotRuntime>[]) new LaneLongCaptures<?>[laneCount];
        for (int laneId = 0; laneId < laneCount; laneId++) {
            patchUsersBeforeByLane[laneId] = new RuntimeUserChangeBuffer();
            patchBalancesBeforeByLane[laneId] = new LaneBalancePatches();
            patchReservationsBeforeByLane[laneId] = new LaneLongCaptures<>();
            patchOrdersBeforeByLane[laneId] = new LaneLongCaptures<>();
            patchPositionsBeforeByLane[laneId] = new LaneLongCaptures<>();
            patchClientOrdersBeforeByLane[laneId] = new LaneClientOrderCaptures();
            patchTriggersBeforeByLane[laneId] = new LaneLongCaptures<>();
            patchRiskSnapshotsBeforeByLane[laneId] = new LaneLongCaptures<>();
        }
    }

    private static final PatchOrderBefore ABSENT_ORDER_BEFORE = new PatchOrderBefore(null, false);
    private static final PatchReservationBefore ABSENT_RESERVATION_BEFORE = new PatchReservationBefore(null, false);

    void captureUserBefore(long userId) {
        if (state.currentMatcherSettlementChangesScope() != null) return;
        RuntimeUserChangeBuffer captured =
                patchUsersBeforeByLane[state.topology.accountLaneId(userId)];
        if (!captured.containsKey(userId)) captured.captureBefore(userId, state.user(userId));
    }

    /**
     * Consume a pipelined batch's Lane-captured before-image at its ordered commit.  Admission has already
     * created the new order and reservation on the Lane, so every order/reservation identity is
     * known to be absent from the owner view.  Avoiding one global lookup plus a cross-Lane capture
     * scan per item removes a quadratic hot path for batch20 while preserving the same contains-key
     * markers used by rollback.
     */
    void captureBatchAdmissionBefore(long userId, UserRuntime userBefore, OrderRuntime[] admittedOrders,
                                             int itemCount, int laneId) {
        if (userId <= 0 || admittedOrders == null || itemCount <= 0 || itemCount > admittedOrders.length
                || laneId < 0 || laneId >= patchOrdersBeforeByLane.length) {
            throw new IllegalArgumentException("invalid batch admission before-image");
        }
        RuntimeUserChangeBuffer userCaptures = patchUsersBeforeByLane[laneId];
        if (!userCaptures.containsKey(userId)) userCaptures.put(userId, userBefore);
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
            PatchOrderBefore captured = orders.value(index);
            OrderRuntime before = captured.value();
            if (before != null) {
                OrderRuntime authority = captured.authority();
                if (authority != before) authority.restoreCapturedValue(before);
                lane.putOrder(authority);
            }
        }
        LaneLongCaptures<PatchReservationBefore> reservations = patchReservationsBeforeByLane[laneId];
        for (int index = 0; index < reservations.size(); index++) {
            long orderId = reservations.key(index);
            PatchReservationBefore captured = reservations.value(index);
            if (captured.pending())
                throw new IllegalStateException("order batch overlapped an existing pending reservation");
            ReservationRuntime current = lane.reservations.remove(orderId);
            if (current != null) {
                lane.removeReservationId(current.userId(), orderId);
            }
            ReservationRuntime before = captured.value();
            if (before != null) {
                lane.reservations.put(orderId, before.laneValue());
                lane.addReservationId(before.userId(), orderId);
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
            // Rollback is itself the final Lane after-state, including failures before any write.
            // Publishing the attempted value (or an uncaptured value) would corrupt the read view.
            balances.after(userId, assetId, userBalances == null ? null : userBalances.get(assetId),
                    lane.pendingReservedUnits(userId, assetId));
        }
        RuntimeUserChangeBuffer users = patchUsersBeforeByLane[laneId];
        for (int index = 0; index < users.size(); index++) {
            long userId = users.keyAt(index);
            UserRuntime before = users.valueAt(index);
            if (before == null) {
                lane.users.remove(userId);
                lane.removeUser(userId);
                lane.clientOrderIndex.remove(userId);
                lane.reservationIdsByUser.remove(userId);
                lane.activeOrderIdsByUser.remove(userId);
            } else {
                UserRuntime authority = users.hasPreparedAt(index) ? users.preparedAt(index) : lane.users.get(userId);
                if (state.accountLanesStarted) {
                    authority = (authority == null ? before : authority).laneValue()
                            .withState(before.revision(), before.positionMode());
                } else authority = before.publicationValue();
                lane.users.put(userId, authority);
                lane.registerUser(userId);
            }
        }
        LaneLongCaptures<CoreTriggerOrderState> triggers = patchTriggersBeforeByLane[laneId];
        for (int index = 0; index < triggers.size(); index++) {
            long triggerId = triggers.key(index);
            CoreTriggerOrderState before = triggers.value(index);
            lane.removeTrigger(triggerId);
            if (before != null) {
                lane.putTrigger(before);
            }
        }
        LaneLongCaptures<RiskSnapshotRuntime> snapshots = patchRiskSnapshotsBeforeByLane[laneId];
        for (int index = 0; index < snapshots.size(); index++) {
            long positionKey = snapshots.key(index);
            RiskSnapshotRuntime before = snapshots.value(index);
            if (before == null) {
                lane.cold.riskSnapshots.remove(positionKey);
            } else {
                lane.cold.riskSnapshots.put(positionKey, before);
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
        for (RuntimeUserChangeBuffer users : patchUsersBeforeByLane)
            for (int index = 0; index < users.size(); index++)
                state.publishUser(users.keyAt(index), users.valueAt(index));
        for (LaneLongCaptures<CoreTriggerOrderState> triggers : patchTriggersBeforeByLane)
            for (int index = 0; index < triggers.size(); index++)
                state.publishTriggerOrder(triggers.key(index), triggers.value(index));
        for (LaneLongCaptures<RiskSnapshotRuntime> snapshots : patchRiskSnapshotsBeforeByLane)
            for (int index = 0; index < snapshots.size(); index++)
                state.publishRiskSnapshot(snapshots.key(index), snapshots.value(index));
    }

    void captureBalanceBefore(long userId, int assetId) {
        MatcherSettlementChanges changes = state.currentMatcherSettlementChangesScope();
        LaneBalancePatches captured = changes == null
                ? patchBalancesBeforeByLane[state.topology.accountLaneId(userId)]
                : changes.balancePatches[state.topology.accountLaneId(userId)];
        if (captured.contains(userId, assetId)) return;
        AccountLaneState scoped = state.currentLaneCommandScope();
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

    void captureBalanceBefore(AccountLaneState lane, long userId, int assetId) {
        MatcherSettlementChanges changes = lane.matcherSettlementChanges;
        LaneBalancePatches captured = changes == null ? patchBalancesBeforeByLane[lane.laneId()]
                : changes.balancePatches[lane.laneId()];
        if (!captured.contains(userId, assetId)) captureBalanceBefore(lane, captured, userId, assetId);
    }

    void captureBalanceAfter(AccountLaneState lane, long userId, int assetId) {
        MatcherSettlementChanges changes = lane.matcherSettlementChanges;
        LaneBalancePatches captured = changes == null
                ? patchBalancesBeforeByLane[lane.laneId()]
                : changes.balancePatches[lane.laneId()];
        IntObjectHashMap<BalanceRuntime> balances = lane.balances.get(userId);
        BalanceRuntime balance = balances == null ? null : balances.get(assetId);
        captured.after(userId, assetId, balance, lane.pendingReservedUnits(userId, assetId));
    }

    void captureOrderBefore(long orderId) {
        captureOrderBefore(orderId, 0);
    }

    void captureOrderBefore(long orderId, long userId) {
        if (state.currentMatcherSettlementChangesScope() != null) return;
        if (capturedOrderBefore(orderId) != null) return;
        OrderRuntime value = state.order(orderId);
        long resolvedUserId = userId > 0 ? userId : (value == null ? 0 : value.userId());
        int laneId = captureLane(orderId, resolvedUserId);
        LaneLongCaptures<PatchOrderBefore> captured = patchOrdersBeforeByLane[laneId];
        if (!captured.containsKey(orderId)) {
            captured.put(orderId, value == null ? ABSENT_ORDER_BEFORE
                    : new PatchOrderBefore(value, state.pendingReservations.pendingReservation(orderId, value.userId())));
        }
    }

    /** Metadata updates retain the live order slot; preserve an existing pre-command value first. */
    void captureOrderMetadataBefore(OrderRuntime order) {
        var captured = patchOrdersBeforeByLane[state.topology.accountLaneId(order.userId())];
        var before = captured.get(order.orderId());
        if (!captured.containsKey(order.orderId()))
            captured.put(order.orderId(), new PatchOrderBefore(order.snapshot(),
                    state.pendingReservations.pendingReservation(order.orderId(), order.userId()), order));
        else if (before != null && before.value() == order)
            captured.replaceReference(order.orderId(), before, new PatchOrderBefore(order.snapshot(), before.pending(), before.authority()));
    }

    void captureReservationBefore(long orderId) {
        captureReservationBefore(orderId, 0);
    }

    void captureReservationBefore(long orderId, long userId) {
        if (state.currentMatcherSettlementChangesScope() != null) return;
        if (capturedReservationBefore(orderId) != null) return;
        ReservationRuntime value = state.reservation(orderId);
        long resolvedUserId = userId > 0 ? userId : (value == null ? 0 : value.userId());
        int laneId = captureLane(orderId, resolvedUserId);
        LaneLongCaptures<PatchReservationBefore> captured = patchReservationsBeforeByLane[laneId];
        if (!captured.containsKey(orderId)) {
            captured.put(orderId, value == null ? ABSENT_RESERVATION_BEFORE
                    : new PatchReservationBefore(value, state.pendingReservations.pendingReservation(orderId, value.userId())));
        }
    }

    int captureLane(long entityId, long userId) {
        AccountLaneState scoped = state.currentLaneCommandScope();
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
        if (state.currentMatcherSettlementChangesScope() != null) return;
        PositionRuntime before = state.position(positionKey);
        long userId = before == null ? fallbackUserId : before.userId();
        if (userId > 0) {
            LaneLongCaptures<PositionRuntime> captured =
                    patchPositionsBeforeByLane[state.topology.accountLaneId(userId)];
            if (!captured.containsKey(positionKey)) captured.put(positionKey, before);
        }
    }

    void captureClientOrderBefore(long userId, long clientKey) {
        if (state.currentMatcherSettlementChangesScope() != null) return;
        LaneClientOrderCaptures captured = patchClientOrdersBeforeByLane[state.topology.accountLaneId(userId)];
        if (!captured.contains(userId, clientKey)) {
            captured.add(userId, clientKey, state.orderIdByClient(userId, clientKey));
        }
    }

    void captureTriggerOrderBefore(long triggerOrderId, long userId) {
        if (state.currentMatcherSettlementChangesScope() != null) return;
        LaneLongCaptures<CoreTriggerOrderState> captured =
                patchTriggersBeforeByLane[state.topology.accountLaneId(userId)];
        if (!captured.containsKey(triggerOrderId)) {
            captured.put(triggerOrderId, state.triggerOrder(triggerOrderId));
        }
    }

    void captureRiskSnapshotBefore(long positionKey, long userId) {
        if (state.currentMatcherSettlementChangesScope() != null) return;
        LaneLongCaptures<RiskSnapshotRuntime> captured =
                patchRiskSnapshotsBeforeByLane[state.topology.accountLaneId(userId)];
        if (!captured.containsKey(positionKey)) {
            RiskSnapshotRuntime before = state.onLane(userId, lane -> lane.cold.riskSnapshots.get(positionKey));
            captured.put(positionKey, before);
        }
    }

    boolean hasCaptured() {
        for (RuntimeUserChangeBuffer captured : patchUsersBeforeByLane)
            if (!captured.isEmpty()) return true;
        for (LaneBalancePatches captured : patchBalancesBeforeByLane)
            if (captured.size() != 0) return true;
        for (LaneLongCaptures<PatchReservationBefore> captured : patchReservationsBeforeByLane)
            if (!captured.isEmpty()) return true;
        for (LaneLongCaptures<PatchOrderBefore> captured : patchOrdersBeforeByLane)
            if (!captured.isEmpty()) return true;
        for (LaneClientOrderCaptures captured : patchClientOrdersBeforeByLane)
            if (captured.size() != 0) return true;
        for (LaneLongCaptures<CoreTriggerOrderState> captured : patchTriggersBeforeByLane)
            if (!captured.isEmpty()) return true;
        for (LaneLongCaptures<RiskSnapshotRuntime> captured : patchRiskSnapshotsBeforeByLane)
            if (!captured.isEmpty()) return true;
        return false;
    }

    void clearNonBalanceImages() {
        for (int laneId = 0; laneId < patchUsersBeforeByLane.length; laneId++) {
            patchUsersBeforeByLane[laneId].clear();
            patchReservationsBeforeByLane[laneId].clear();
            patchOrdersBeforeByLane[laneId].clear();
            patchPositionsBeforeByLane[laneId].clear();
            patchClientOrdersBeforeByLane[laneId].clear();
            patchTriggersBeforeByLane[laneId].clear();
            patchRiskSnapshotsBeforeByLane[laneId].clear();
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
        for (LaneLongCaptures<PositionRuntime> captured : patchPositionsBeforeByLane)
            if (captured.containsKey(positionKey)) return captured.get(positionKey);
        if (state.currentLaneCommandScope() != null || !state.accountLanesStarted)
            return state.position(positionKey);
        return state.changedPositionValue(positionKey);
    }

    OrderRuntime currentOrderBefore(long orderId) {
        state.assertOwner();
        PatchOrderBefore captured = capturedOrderBefore(orderId);
        return captured == null ? state.order(orderId) : captured.value();
    }

    record PatchOrderBefore(OrderRuntime value, boolean pending, OrderRuntime authority) {
        PatchOrderBefore(OrderRuntime value, boolean pending) { this(value, pending, value); }
    }
    record PatchReservationBefore(ReservationRuntime value, boolean pending) {}
}

final class LaneClientOrderCaptures {
    /** 当前缓冲各项对应的用户 ID。 */
    long[] userIds = new long[4];
    /** 当前缓冲各项对应的内部客户单号键。 */
    long[] clientKeys = new long[4];
    /** 变更前客户单号指向的订单 ID。 */
    long[] beforeOrderIds = new long[4];
    /** 该实体在变更前是否存在，区分不存在和零值。 */
    boolean[] presentBefore = new boolean[4];
    /** 当前有效元素数量。 */
    int size;

    int size() { return size; }
    long userId(int index) { return userIds[index]; }
    long clientKey(int index) { return clientKeys[index]; }
    Long beforeOrderId(int index) {
        return presentBefore[index] ? beforeOrderIds[index] : null;
    }

    boolean contains(long userId, long clientKey) {
        for (int index = 0; index < size; index++) {
            if (userIds[index] == userId && clientKeys[index] == clientKey) return true;
        }
        return false;
    }

    void add(long userId, long clientKey, Long beforeOrderId) {
        if (userId <= 0 || clientKey <= 0) {
            throw new IllegalArgumentException("invalid client-order capture");
        }
        if (size == userIds.length) {
            int capacity = Math.multiplyExact(size, 2);
            userIds = java.util.Arrays.copyOf(userIds, capacity);
            clientKeys = java.util.Arrays.copyOf(clientKeys, capacity);
            beforeOrderIds = java.util.Arrays.copyOf(beforeOrderIds, capacity);
            presentBefore = java.util.Arrays.copyOf(presentBefore, capacity);
        }
        userIds[size] = userId;
        clientKeys[size] = clientKey;
        presentBefore[size] = beforeOrderId != null;
        beforeOrderIds[size] = beforeOrderId == null ? 0 : beforeOrderId;
        size++;
    }

    void clear() {
        size = 0;
    }
}

final class LaneLongCaptures<V> {
    private long[] keys = new long[8];
    private Object[] values = new Object[8];
    private long[] indexKeys = new long[16];
    private int[] indexSlots = new int[16];
    private int[] indexGenerations = new int[16];
    private int indexGeneration = 1;
    int size;

    int size() { return size; }
    boolean isEmpty() { return size == 0; }

    boolean containsKey(long key) { return indexOf(key) >= 0; }

    V get(long key) {
        int index = indexOf(key);
        return index < 0 ? null : value(index);
    }

    void put(long key, V value) {
        if (indexOf(key) >= 0) throw new IllegalStateException("capture key already exists");
        ensureIndexCapacity(size + 1);
        if (size == keys.length) {
            int capacity = Math.multiplyExact(size, 2);
            keys = java.util.Arrays.copyOf(keys, capacity);
            values = java.util.Arrays.copyOf(values, capacity);
        }
        int indexPosition = emptyIndexPosition(key);
        keys[size] = key;
        values[size] = value;
        indexKeys[indexPosition] = key;
        indexSlots[indexPosition] = size;
        indexGenerations[indexPosition] = indexGeneration;
        size++;
    }

    void replaceReference(long key, V expected, V frozen) {
        int slot = indexOf(key);
        if (slot < 0 || values[slot] != expected)
            throw new IllegalStateException("captured reference changed before freezing");
        values[slot] = frozen;
    }

    long key(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException(index);
        return keys[index];
    }

    @SuppressWarnings("unchecked")
    V value(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException(index);
        return (V) values[index];
    }

    int indexOf(long key) {
        if (size == 0) return -1;
        int mask = indexSlots.length - 1;
        int position = longHash(key) & mask;
        while (indexGenerations[position] == indexGeneration) {
            if (indexKeys[position] == key) return indexSlots[position];
            position = (position + 1) & mask;
        }
        return -1;
    }

    private int emptyIndexPosition(long key) {
        int mask = indexSlots.length - 1;
        int position = longHash(key) & mask;
        while (indexGenerations[position] == indexGeneration) {
            position = (position + 1) & mask;
        }
        return position;
    }

    private void ensureIndexCapacity(int requiredSize) {
        if (requiredSize <= indexSlots.length / 2) return;
        int capacity = Math.multiplyExact(indexSlots.length, 2);
        indexKeys = new long[capacity];
        indexSlots = new int[capacity];
        indexGenerations = new int[capacity];
        indexGeneration = 1;
        for (int index = 0; index < size; index++) {
            int position = emptyIndexPosition(keys[index]);
            indexKeys[position] = keys[index];
            indexSlots[position] = index;
            indexGenerations[position] = indexGeneration;
        }
    }

    static int longHash(long key) {
        key ^= key >>> 33;
        key *= 0xff51afd7ed558ccdL;
        key ^= key >>> 33;
        return (int) key;
    }

    void clear() {
        if (size == 0) return;
        for (int index = 0; index < size; index++) values[index] = null;
        size = 0;
        if (++indexGeneration == 0) {
            java.util.Arrays.fill(indexGenerations, 0);
            indexGeneration = 1;
        }
    }
}
