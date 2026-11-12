package com.surprising.aeron.service.account;

import com.surprising.aeron.service.order.*;

import com.surprising.aeron.model.CoreTriggerOrderState;
import com.surprising.aeron.service.core.AccountBook;
import com.surprising.aeron.service.core.change.StateChangeBuffer;
import com.surprising.aeron.service.order.Order;
import com.surprising.aeron.service.order.OrderFundsReservation;
import com.surprising.aeron.service.position.Position;
import com.surprising.aeron.service.risk.RiskMarginCalculator;
import com.surprising.aeron.service.settlement.TradeSettlement;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.experimental.Accessors;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;

/** 按账户分片保存当前命令首次修改前的前值；回滚先由分片恢复权威数据，再由 Owner 恢复发布引用。 */
public final class AccountTransactionRollback {
    private final AccountBook state;
    final StateChangeBuffer.Users[] patchUsersBeforeByLane;
    final BalancePatches[] patchBalancesBeforeByLane;
    final LongCaptures<PatchReservationBefore>[] patchReservationsBeforeByLane;
    /** 按分片保存订单原值、待完成标记和恢复用权威引用。 */
    final LongCaptures<PatchOrderBefore>[] patchOrdersBeforeByLane;
    final LongCaptures<Position>[] patchPositionsBeforeByLane;
    final ClientOrderCaptures[] patchClientOrdersBeforeByLane;
    final LongCaptures<CoreTriggerOrderState>[] patchTriggersBeforeByLane;
    final LongCaptures<RiskMarginCalculator.Snapshot>[] patchRiskSnapshotsBeforeByLane;

    /** 按实际分片数量创建各类可复用捕获缓冲，不复制整份账户状态，也不启动工作线程。 */
    @SuppressWarnings("unchecked")
    public AccountTransactionRollback(AccountBook state, int laneCount) {
        this.state = java.util.Objects.requireNonNull(state);
        patchUsersBeforeByLane = new StateChangeBuffer.Users[laneCount];
        patchBalancesBeforeByLane = new BalancePatches[laneCount];
        patchReservationsBeforeByLane = (LongCaptures<PatchReservationBefore>[]) new LongCaptures<?>[laneCount];
        patchOrdersBeforeByLane = (LongCaptures<PatchOrderBefore>[]) new LongCaptures<?>[laneCount];
        patchPositionsBeforeByLane = (LongCaptures<Position>[]) new LongCaptures<?>[laneCount];
        patchClientOrdersBeforeByLane = new ClientOrderCaptures[laneCount];
        patchTriggersBeforeByLane = (LongCaptures<CoreTriggerOrderState>[]) new LongCaptures<?>[laneCount];
        patchRiskSnapshotsBeforeByLane = (LongCaptures<RiskMarginCalculator.Snapshot>[]) new LongCaptures<?>[laneCount];
        for (int laneId = 0; laneId < laneCount; laneId++) {
            patchUsersBeforeByLane[laneId] = new StateChangeBuffer.Users();
            patchBalancesBeforeByLane[laneId] = new BalancePatches();
            patchReservationsBeforeByLane[laneId] = new LongCaptures<>();
            patchOrdersBeforeByLane[laneId] = new LongCaptures<>();
            patchPositionsBeforeByLane[laneId] = new LongCaptures<>();
            patchClientOrdersBeforeByLane[laneId] = new ClientOrderCaptures();
            patchTriggersBeforeByLane[laneId] = new LongCaptures<>();
            patchRiskSnapshotsBeforeByLane[laneId] = new LongCaptures<>();
        }
    }

    /** 原订单不存在且没有待完成准入的共享捕获记录，避免为每个新增订单创建哨兵对象。 */
    private static final PatchOrderBefore ABSENT_ORDER_BEFORE = new PatchOrderBefore(null, false);
    private static final PatchReservationBefore ABSENT_RESERVATION_BEFORE = new PatchReservationBefore(null, false);

    public void captureUserBefore(long userId) {
        if (state.currentMatcherSettlementChangesScope() != null) return;
        StateChangeBuffer.Users captured =
                patchUsersBeforeByLane[state.topology().accountLaneId(userId)];
        if (!captured.containsKey(userId)) captured.captureBefore(userId, state.user(userId));
    }

    public void captureBatchOrderCheckBefore(long userId, UserAccount userBefore, Order[] admittedOrders,
                                            int itemCount, int laneId) {
        if (userId <= 0 || admittedOrders == null || itemCount <= 0 || itemCount > admittedOrders.length
                || laneId < 0 || laneId >= patchOrdersBeforeByLane.length) {
            throw new IllegalArgumentException("invalid batch admission before-image");
        }
        StateChangeBuffer.Users userCaptures = patchUsersBeforeByLane[laneId];
        if (!userCaptures.containsKey(userId)) userCaptures.put(userId, userBefore);
        LongCaptures<PatchOrderBefore> orderCaptures = patchOrdersBeforeByLane[laneId];
        LongCaptures<PatchReservationBefore> reservationCaptures = patchReservationsBeforeByLane[laneId];
        for (int index = 0; index < itemCount; index++) {
            Order admitted = admittedOrders[index];
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

    public void restoreLane(AccountPartition lane) {
        lane.assertOwner();
        int laneId = lane.laneId();
        ClientOrderCaptures clients = patchClientOrdersBeforeByLane[laneId];
        for (int index = 0; index < clients.size(); index++) {
            long userId = clients.userId(index);
            long clientKey = clients.clientKey(index);
            AccountBook.removeClientOrderIndex(lane, userId, clientKey);
            Long before = clients.beforeOrderId(index);
            if (before != null) AccountBook.putClientOrderIndex(lane, userId, clientKey, before);
        }
        LongCaptures<PatchOrderBefore> orders = patchOrdersBeforeByLane[laneId];
        for (int index = 0; index < orders.size(); index++) {
            lane.removeOrder(orders.key(index));
            PatchOrderBefore captured = orders.value(index);
            Order before = captured.value();
            if (before != null) {
                Order authority = captured.authority();
                if (authority != before) authority.restoreCapturedValue(before);
                lane.putOrder(authority);
            }
        }
        LongCaptures<PatchReservationBefore> reservations = patchReservationsBeforeByLane[laneId];
        for (int index = 0; index < reservations.size(); index++) {
            long orderId = reservations.key(index);
            PatchReservationBefore captured = reservations.value(index);
            if (captured.pending())
                throw new IllegalStateException("order batch overlapped an existing pending reservation");
            OrderFundsReservation current = lane.reservations.remove(orderId);
            if (current != null) {
                lane.removeReservationId(current.userId(), orderId);
            }
            OrderFundsReservation before = captured.value();
            if (before != null) {
                lane.reservations.put(orderId, before.laneValue());
                lane.addReservationId(before.userId(), orderId);
            }
        }
        LongCaptures<Position> positions = patchPositionsBeforeByLane[laneId];
        for (int index = 0; index < positions.size(); index++) {
            long positionKey = positions.key(index);
            Position current = lane.positions.remove(positionKey);
            if (current != null) AccountBook.unindexPosition(lane, positionKey, current);
            Position before = positions.value(index);
            if (before != null) {
                lane.positions.put(positionKey, before.laneValue());
                AccountBook.indexPosition(lane, positionKey, before);
            }
        }
        BalancePatches balances = patchBalancesBeforeByLane[laneId];
        for (int index = 0; index < balances.size(); index++) {
            long userId = balances.userId(index);
            int assetId = balances.assetId(index);
            IntObjectHashMap<AccountBalance> userBalances = lane.balances.get(userId);
            if (!balances.presentBefore(index)) {
                if (userBalances != null) {
                    userBalances.remove(assetId);
                    if (userBalances.isEmpty()) lane.balances.remove(userId);
                }
            } else {
                if (userBalances == null) {
                    userBalances = new IntObjectHashMap<>();
                    lane.balances.put(userId, userBalances);
                }
                userBalances.put(assetId, new AccountBalance(userId, assetId,
                        balances.availableBefore(index), balances.lockedBefore(index)));
            }
            balances.after(userId, assetId, userBalances == null ? null : userBalances.get(assetId),
                    lane.pendingReservedUnits(userId, assetId));
        }
        StateChangeBuffer.Users users = patchUsersBeforeByLane[laneId];
        for (int index = 0; index < users.size(); index++) {
            long userId = users.keyAt(index);
            UserAccount before = users.valueAt(index);
            if (before == null) {
                lane.users.remove(userId);
                lane.removeUser(userId);
                lane.clientIdentities.clientOrderIndex.remove(userId);
                lane.orderCheckOrderIndex.reservationIdsByUser.remove(userId);
                lane.orderCheckOrderIndex.activeOrderIdsByUser.remove(userId);
            } else {
                UserAccount authority = users.hasPreparedAt(index) ? users.preparedAt(index) : lane.users.get(userId);
                if (state.accountLanesStarted()) {
                    authority = (authority == null ? before : authority).laneValue()
                            .withState(before.revision(), before.positionMode());
                } else authority = before.publicationValue();
                lane.users.put(userId, authority);
                lane.registerUser(userId);
            }
        }
        LongCaptures<CoreTriggerOrderState> triggers = patchTriggersBeforeByLane[laneId];
        for (int index = 0; index < triggers.size(); index++) {
            long triggerId = triggers.key(index);
            CoreTriggerOrderState before = triggers.value(index);
            lane.removeTrigger(triggerId);
            if (before != null) {
                lane.putTrigger(before);
            }
        }
        LongCaptures<RiskMarginCalculator.Snapshot> snapshots = patchRiskSnapshotsBeforeByLane[laneId];
        for (int index = 0; index < snapshots.size(); index++) {
            long positionKey = snapshots.key(index);
            RiskMarginCalculator.Snapshot before = snapshots.value(index);
            if (before == null) {
                lane.cold.riskSnapshots.remove(positionKey);
            } else {
                lane.cold.riskSnapshots.put(positionKey, before);
            }
        }
    }

    public void restoreOwner() {
        state.assertOwner();
        for (LongCaptures<PatchOrderBefore> orders : patchOrdersBeforeByLane)
            for (int index = 0; index < orders.size(); index++)
                state.publishOrder(orders.key(index), orders.value(index).value());
        for (LongCaptures<PatchReservationBefore> reservations : patchReservationsBeforeByLane)
            for (int index = 0; index < reservations.size(); index++)
                state.publishReservation(reservations.key(index), reservations.value(index).value());
        for (LongCaptures<Position> positions : patchPositionsBeforeByLane)
            for (int index = 0; index < positions.size(); index++)
                state.publishPosition(positions.key(index), positions.value(index));
        for (StateChangeBuffer.Users users : patchUsersBeforeByLane)
            for (int index = 0; index < users.size(); index++)
                state.publishUser(users.keyAt(index), users.valueAt(index));
        for (LongCaptures<CoreTriggerOrderState> triggers : patchTriggersBeforeByLane)
            for (int index = 0; index < triggers.size(); index++)
                state.publishTriggerOrder(triggers.key(index), triggers.value(index));
        for (LongCaptures<RiskMarginCalculator.Snapshot> snapshots : patchRiskSnapshotsBeforeByLane)
            for (int index = 0; index < snapshots.size(); index++)
                state.publishRiskSnapshot(snapshots.key(index), snapshots.value(index));
    }

    public void captureBalanceBefore(long userId, int assetId) {
        TradeSettlement.Changes changes = state.currentMatcherSettlementChangesScope();
        BalancePatches captured = changes == null
                ? patchBalancesBeforeByLane[state.topology().accountLaneId(userId)]
                : changes.balancePatch(state.topology().accountLaneId(userId));
        if (captured.contains(userId, assetId)) return;
        AccountPartition scoped = state.currentLaneCommandScope();
        if (scoped != null) {
            captureBalanceBefore(scoped, captured, userId, assetId);
            return;
        }
        state.onLane(userId, lane -> {
            captureBalanceBefore(lane, captured, userId, assetId);
            return null;
        });
    }

    public static void captureBalanceBefore(AccountPartition lane, BalancePatches captured,
                                            long userId, int assetId) {
        IntObjectHashMap<AccountBalance> balances = lane.balances.get(userId);
        AccountBalance balance = balances == null ? null : balances.get(assetId);
        captured.add(userId, assetId, balance, lane.pendingReservedUnits(userId, assetId));
    }

    public void captureBalanceBefore(AccountPartition lane, long userId, int assetId) {
        TradeSettlement.Changes changes = lane.matcherSettlementChanges;
        BalancePatches captured = changes == null ? patchBalancesBeforeByLane[lane.laneId()]
                : changes.balancePatch(lane.laneId());
        if (!captured.contains(userId, assetId)) captureBalanceBefore(lane, captured, userId, assetId);
    }

    public void captureBalanceAfter(AccountPartition lane, long userId, int assetId) {
        TradeSettlement.Changes changes = lane.matcherSettlementChanges;
        BalancePatches captured = changes == null
                ? patchBalancesBeforeByLane[lane.laneId()]
                : changes.balancePatch(lane.laneId());
        IntObjectHashMap<AccountBalance> balances = lane.balances.get(userId);
        AccountBalance balance = balances == null ? null : balances.get(assetId);
        captured.after(userId, assetId, balance, lane.pendingReservedUnits(userId, assetId));
    }

    public void captureOrderBefore(long orderId) {
        captureOrderBefore(orderId, 0);
    }

    public void captureOrderBefore(long orderId, long userId) {
        if (state.currentMatcherSettlementChangesScope() != null) return;
        if (capturedOrderBefore(orderId) != null) return;
        Order value = state.order(orderId);
        long resolvedUserId = userId > 0 ? userId : (value == null ? 0 : value.userId());
        int laneId = captureLane(orderId, resolvedUserId);
        LongCaptures<PatchOrderBefore> captured = patchOrdersBeforeByLane[laneId];
        if (!captured.containsKey(orderId)) {
            captured.put(orderId, value == null ? ABSENT_ORDER_BEFORE
                    : new PatchOrderBefore(value, state.pendingReservations().pendingReservation(orderId, value.userId())));
        }
    }

    public void captureOrderMetadataBefore(Order order) {
        var captured = patchOrdersBeforeByLane[state.topology().accountLaneId(order.userId())];
        var before = captured.get(order.orderId());
        if (!captured.containsKey(order.orderId()))
            captured.put(order.orderId(), new PatchOrderBefore(order.snapshot(),
                    state.pendingReservations().pendingReservation(order.orderId(), order.userId()), order));
        else if (before != null && before.value() == order)
            captured.replaceReference(order.orderId(), before, new PatchOrderBefore(order.snapshot(), before.pending(), before.authority()));
    }

    public void captureReservationBefore(long orderId) {
        captureReservationBefore(orderId, 0);
    }

    public void captureReservationBefore(long orderId, long userId) {
        if (state.currentMatcherSettlementChangesScope() != null) return;
        if (capturedReservationBefore(orderId) != null) return;
        OrderFundsReservation value = state.reservation(orderId);
        long resolvedUserId = userId > 0 ? userId : (value == null ? 0 : value.userId());
        int laneId = captureLane(orderId, resolvedUserId);
        LongCaptures<PatchReservationBefore> captured = patchReservationsBeforeByLane[laneId];
        if (!captured.containsKey(orderId)) {
            captured.put(orderId, value == null ? ABSENT_RESERVATION_BEFORE
                    : new PatchReservationBefore(value, state.pendingReservations().pendingReservation(orderId, value.userId())));
        }
    }

    int captureLane(long entityId, long userId) {
        AccountPartition scoped = state.currentLaneCommandScope();
        if (scoped != null) return scoped.laneId();
        if (userId > 0) return state.topology().accountLaneId(userId);
        return Math.floorMod(Long.hashCode(entityId), state.accountLaneCount());
    }

    PatchOrderBefore capturedOrderBefore(long orderId) {
        for (LongCaptures<PatchOrderBefore> captured : patchOrdersBeforeByLane) {
            PatchOrderBefore value = captured.get(orderId);
            if (value != null || captured.containsKey(orderId)) return value;
        }
        return null;
    }

    PatchReservationBefore capturedReservationBefore(long orderId) {
        for (LongCaptures<PatchReservationBefore> captured : patchReservationsBeforeByLane) {
            PatchReservationBefore value = captured.get(orderId);
            if (value != null || captured.containsKey(orderId)) return value;
        }
        return null;
    }

    public void capturePositionBefore(long positionKey) {
        capturePositionBefore(positionKey, 0);
    }

    public void capturePositionBefore(long positionKey, long fallbackUserId) {
        if (state.currentMatcherSettlementChangesScope() != null) return;
        Position before = state.position(positionKey);
        long userId = before == null ? fallbackUserId : before.userId();
        if (userId > 0) {
            LongCaptures<Position> captured =
                    patchPositionsBeforeByLane[state.topology().accountLaneId(userId)];
            if (!captured.containsKey(positionKey)) captured.put(positionKey, before);
        }
    }

    public void captureClientOrderBefore(long userId, long clientKey) {
        if (state.currentMatcherSettlementChangesScope() != null) return;
        ClientOrderCaptures captured = patchClientOrdersBeforeByLane[state.topology().accountLaneId(userId)];
        if (!captured.contains(userId, clientKey)) {
            captured.add(userId, clientKey, state.orderIdByClient(userId, clientKey));
        }
    }

    public void captureTriggerOrderBefore(long triggerOrderId, long userId) {
        if (state.currentMatcherSettlementChangesScope() != null) return;
        LongCaptures<CoreTriggerOrderState> captured =
                patchTriggersBeforeByLane[state.topology().accountLaneId(userId)];
        if (!captured.containsKey(triggerOrderId)) {
            captured.put(triggerOrderId, state.triggerOrder(triggerOrderId));
        }
    }

    public void captureRiskSnapshotBefore(long positionKey, long userId) {
        if (state.currentMatcherSettlementChangesScope() != null) return;
        LongCaptures<RiskMarginCalculator.Snapshot> captured =
                patchRiskSnapshotsBeforeByLane[state.topology().accountLaneId(userId)];
        if (!captured.containsKey(positionKey)) {
            RiskMarginCalculator.Snapshot before = state.onLane(userId, lane -> lane.cold.riskSnapshots.get(positionKey));
            captured.put(positionKey, before);
        }
    }

    public boolean hasCaptured() {
        for (StateChangeBuffer.Users captured : patchUsersBeforeByLane)
            if (!captured.isEmpty()) return true;
        for (BalancePatches captured : patchBalancesBeforeByLane)
            if (captured.size() != 0) return true;
        for (LongCaptures<PatchReservationBefore> captured : patchReservationsBeforeByLane)
            if (!captured.isEmpty()) return true;
        for (LongCaptures<PatchOrderBefore> captured : patchOrdersBeforeByLane)
            if (!captured.isEmpty()) return true;
        for (ClientOrderCaptures captured : patchClientOrdersBeforeByLane)
            if (captured.size() != 0) return true;
        for (LongCaptures<CoreTriggerOrderState> captured : patchTriggersBeforeByLane)
            if (!captured.isEmpty()) return true;
        for (LongCaptures<RiskMarginCalculator.Snapshot> captured : patchRiskSnapshotsBeforeByLane)
            if (!captured.isEmpty()) return true;
        return false;
    }

    public void clearNonBalanceImages() {
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

    public void publishAndClearBalances() {
        for (BalancePatches captured : patchBalancesBeforeByLane) {
            for (int index = 0; index < captured.size(); index++)
                captured.publishAvailableAt(state, index);
            captured.clear();
        }
    }

    public Position currentPositionBefore(long positionKey) {
        state.assertOwner();
        for (LongCaptures<Position> captured : patchPositionsBeforeByLane)
            if (captured.containsKey(positionKey)) return captured.get(positionKey);
        if (state.currentLaneCommandScope() != null || !state.accountLanesStarted())
            return state.position(positionKey);
        return state.changedPositionValue(positionKey);
    }

    record PatchOrderBefore(Order value, boolean pending, Order authority) {
        PatchOrderBefore(Order value, boolean pending) { this(value, pending, value); }
    }

    record PatchReservationBefore(OrderFundsReservation value, boolean pending) {}

    public BalancePatches balancePatch(int index) { return patchBalancesBeforeByLane[index]; }
    public int balancePatchCount() { return patchBalancesBeforeByLane.length; }

    /** 一个命令在各用户资产上捕获的余额前后值。 */
    public static final class BalancePatches {
        public void mergeBefore(BalancePatches source) {
            for (int index = 0; index < source.size; index++) {
                long userId = source.userIds[index];
                int assetId = source.assetIds[index];
                if (contains(userId, assetId)) continue;
                int target = size;
                add(userId, assetId, null, 0);
                presentBefore[target] = source.presentBefore[index];
                availableBefore[target] = source.availableBefore[index];
                lockedBefore[target] = source.lockedBefore[index];
                pendingBefore[target] = source.pendingBefore[index];
            }
        }

        public void mergeSequential(BalancePatches source) {
            for (int sourceIndex = 0; sourceIndex < source.size; sourceIndex++) {
                long userId = source.userIds[sourceIndex];
                int assetId = source.assetIds[sourceIndex];
                int target = indexOf(userId, assetId);
                if (target < 0) {
                    target = size;
                    add(userId, assetId, null, 0);
                    presentBefore[target] = source.presentBefore[sourceIndex];
                    availableBefore[target] = source.availableBefore[sourceIndex];
                    lockedBefore[target] = source.lockedBefore[sourceIndex];
                    pendingBefore[target] = source.pendingBefore[sourceIndex];
                }
                presentAfter[target] = source.presentAfter[sourceIndex];
                capturedAfter[target] = source.capturedAfter[sourceIndex];
                availableAfter[target] = source.availableAfter[sourceIndex];
                lockedAfter[target] = source.lockedAfter[sourceIndex];
                pendingAfter[target] = source.pendingAfter[sourceIndex];
            }
        }

        long[] userIds = new long[8];
        int[] assetIds = new int[8];
        long[] availableBefore = new long[8];
        long[] lockedBefore = new long[8];
        long[] pendingBefore = new long[8];
        boolean[] presentBefore = new boolean[8];
        long[] availableAfter = new long[8];
        long[] lockedAfter = new long[8];
        long[] pendingAfter = new long[8];
        boolean[] presentAfter = new boolean[8];
        boolean[] capturedAfter = new boolean[8];
        long[] indexUserIds = new long[16];
        int[] indexAssetIds = new int[16];
        int[] indexSlots = new int[16];
        int[] indexGenerations = new int[16];
        int indexGeneration = 1;
        @Accessors(fluent = true, chain = false)
        @Getter
        int size;

        public long userId(int index) { return userIds[index]; }
        public int assetId(int index) { return assetIds[index]; }
        boolean contains(long userId, int assetId) {
            return indexOf(userId, assetId) >= 0;
        }

        public void add(long userId, int assetId, AccountBalance value, long pendingReservedUnits) {
            if (indexOf(userId, assetId) >= 0) {
                throw new IllegalStateException("balance capture key already exists");
            }
            if (value != null && (pendingReservedUnits < 0 || pendingReservedUnits > value.lockedUnits())) {
                throw new IllegalArgumentException("invalid runtime balance state");
            }
            ensureIndexCapacity(size + 1);
            if (size == userIds.length) {
                int capacity = Math.multiplyExact(size, 2);
                userIds = java.util.Arrays.copyOf(userIds, capacity);
                assetIds = java.util.Arrays.copyOf(assetIds, capacity);
                availableBefore = java.util.Arrays.copyOf(availableBefore, capacity);
                lockedBefore = java.util.Arrays.copyOf(lockedBefore, capacity);
                pendingBefore = java.util.Arrays.copyOf(pendingBefore, capacity);
                presentBefore = java.util.Arrays.copyOf(presentBefore, capacity);
                availableAfter = java.util.Arrays.copyOf(availableAfter, capacity);
                lockedAfter = java.util.Arrays.copyOf(lockedAfter, capacity);
                pendingAfter = java.util.Arrays.copyOf(pendingAfter, capacity);
                presentAfter = java.util.Arrays.copyOf(presentAfter, capacity);
                capturedAfter = java.util.Arrays.copyOf(capturedAfter, capacity);
            }
            int indexPosition = emptyIndexPosition(userId, assetId);
            userIds[size] = userId;
            assetIds[size] = assetId;
            capturedAfter[size] = false;
            presentAfter[size] = false;
            presentBefore[size] = value != null;
            if (value != null) {
                availableBefore[size] = value.availableUnits();
                lockedBefore[size] = value.lockedUnits();
                pendingBefore[size] = pendingReservedUnits;
            }
            indexUserIds[indexPosition] = userId;
            indexAssetIds[indexPosition] = assetId;
            indexSlots[indexPosition] = size;
            indexGenerations[indexPosition] = indexGeneration;
            size++;
        }

        public void after(long userId, int assetId, AccountBalance value, long pendingReservedUnits) {
            int index = indexOf(userId, assetId);
            if (index < 0) {
                throw new IllegalStateException("balance after-state is missing its before-state");
            }
            capturedAfter[index] = true;
            presentAfter[index] = value != null;
            if (value != null) {
                availableAfter[index] = value.availableUnits();
                lockedAfter[index] = value.lockedUnits();
                pendingAfter[index] = pendingReservedUnits;
            }
        }

        public void clear() {
            if (size == 0) return;
            size = 0;
            if (++indexGeneration == 0) {
                java.util.Arrays.fill(indexGenerations, 0);
                indexGeneration = 1;
            }
        }

        public void publishAvailableAt(AccountBook state, int index) {
            if (index < 0 || index >= size || !capturedAfter[index]) {
                throw new IllegalStateException("balance mutation did not publish its lane after-state");
            }
            long userId = userIds[index];
            int assetId = assetIds[index];
            if (state.realtimeCapture() != null) state.realtimeCapture().balance(userId, assetId,
                    presentAfter[index] ? availableAfter[index] : 0,
                    presentAfter[index] ? lockedAfter[index] : 0);
        }

        int indexOf(long userId, int assetId) {
            int mask = indexSlots.length - 1;
            int position = pairHash(userId, assetId) & mask;
            while (indexGenerations[position] == indexGeneration) {
                if (indexUserIds[position] == userId && indexAssetIds[position] == assetId) {
                    return indexSlots[position];
                }
                position = (position + 1) & mask;
            }
            return -1;
        }

        int emptyIndexPosition(long userId, int assetId) {
            int mask = indexSlots.length - 1;
            int position = pairHash(userId, assetId) & mask;
            while (indexGenerations[position] == indexGeneration) position = (position + 1) & mask;
            return position;
        }

        void ensureIndexCapacity(int requiredSize) {
            if (requiredSize <= indexSlots.length / 2) return;
            int capacity = Math.multiplyExact(indexSlots.length, 2);
            indexUserIds = new long[capacity];
            indexAssetIds = new int[capacity];
            indexSlots = new int[capacity];
            indexGenerations = new int[capacity];
            indexGeneration = 1;
            for (int index = 0; index < size; index++) {
                int position = emptyIndexPosition(userIds[index], assetIds[index]);
                indexUserIds[position] = userIds[index];
                indexAssetIds[position] = assetIds[index];
                indexSlots[position] = index;
                indexGenerations[position] = indexGeneration;
            }
        }

        static int pairHash(long userId, int assetId) {
            long value = userId ^ Integer.toUnsignedLong(assetId) * 0x9e3779b97f4a7c15L;
            value ^= value >>> 33;
            value *= 0xff51afd7ed558ccdL;
            value ^= value >>> 33;
            return (int) value;
        }

        public long availableAfter(int index) { return availableAfter[index]; }
        public long availableBefore(int index) { return availableBefore[index]; }
        public boolean capturedAfter(int index) { return capturedAfter[index]; }
        public long lockedAfter(int index) { return lockedAfter[index]; }
        public long lockedBefore(int index) { return lockedBefore[index]; }
        public boolean presentAfter(int index) { return presentAfter[index]; }
        public boolean presentBefore(int index) { return presentBefore[index]; }
    }

    /** 一个命令中用户加客户身份键的原订单指向捕获。用连续原语数组保存前值及存在标记，供回滚恢复正反向索引。 */
    public static final class ClientOrderCaptures {
        long[] userIds = new long[4];
        long[] clientKeys = new long[4];
        long[] beforeOrderIds = new long[4];
        boolean[] presentBefore = new boolean[4];
        @Accessors(fluent = true, chain = false)
        @Getter(value = AccessLevel.PACKAGE)
        int size;

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

    /** 一个分片、一个命令的 long 键前值捕获缓冲。按首次捕获顺序保存键和值引用，另用带代次的索引定位。 */
    static final class LongCaptures<V> {
        private long[] keys = new long[8];
        private Object[] values = new Object[8];
        private long[] indexKeys = new long[16];
        private int[] indexSlots = new int[16];
        private int[] indexGenerations = new int[16];
        private int indexGeneration = 1;
        @Accessors(fluent = true, chain = false)
        @Getter(value = AccessLevel.PACKAGE)
        int size;

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
}
