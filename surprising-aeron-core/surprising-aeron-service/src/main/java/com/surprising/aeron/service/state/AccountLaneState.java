package com.surprising.aeron.service.state;

import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongIntHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;
import java.util.Map;
import java.util.HashMap;
import java.util.TreeSet;

public final class AccountLaneState {
    private final int laneId;
    private final int queueCapacity;
    private final LongHashSet userIds = new LongHashSet();
    final LongObjectHashMap<UserRuntime> users = new LongObjectHashMap<>();
    final LongObjectHashMap<IntObjectHashMap<BalanceRuntime>> balances = new LongObjectHashMap<>();
    final LongObjectHashMap<OrderRuntime> orders = new LongObjectHashMap<>();
    final LongObjectHashMap<ReservationRuntime> reservations = new LongObjectHashMap<>();
    final LongObjectHashMap<LongHashSet> reservationIdsByUser = new LongObjectHashMap<>();
    final LongObjectHashMap<PositionRuntime> positions = new LongObjectHashMap<>();
    final LongObjectHashMap<LongHashSet> positionKeysByUser = new LongObjectHashMap<>();
    final IntObjectHashMap<LongObjectHashMap<TreeSet<Long>>> positionKeysBySymbolAndUser
            = new IntObjectHashMap<>();
    final LongObjectHashMap<LiquidationRuntime> liquidations = new LongObjectHashMap<>();
    final LongObjectHashMap<IntObjectHashMap<LongObjectHashMap<Long>>> activeLiquidationIndex
            = new LongObjectHashMap<>();
    final LongObjectHashMap<RiskSnapshotRuntime> riskSnapshots = new LongObjectHashMap<>();
    final LongObjectHashMap<LongObjectHashMap<Long>> clientOrderIndex = new LongObjectHashMap<>();
    final LongObjectHashMap<LongHashSet> clientKeysByOrderId = new LongObjectHashMap<>();
    final Map<CoreLeverageKey, Long> leverages = new HashMap<>();
    final LongObjectHashMap<TreeSet<CoreLeverageKey>> leverageKeysByUser = new LongObjectHashMap<>();
    final Map<Long, CoreAlgoOrderState> algoOrders = new HashMap<>();
    final Map<Long, CoreTriggerOrderState> triggerOrders = new HashMap<>();
    final LongLongHashMap pendingReservationSequences = new LongLongHashMap();
    private final LongIntHashMap pendingReservationCountsByUser = new LongIntHashMap();
    private final LongObjectHashMap<IntLongHashMap> pendingReservedUnitsByUser = new LongObjectHashMap<>();
    private int totalPendingReservations;
    private long revision;
    private long appliedSequence;
    private long committedSequence;
    private long localStateHash = 0xcbf29ce484222325L;
    private long localFundsHash = 0xcbf29ce484222325L;
    private Thread owner;

    AccountLaneState(int laneId, int queueCapacity) {
        if (laneId < 0 || laneId >= Long.SIZE || queueCapacity <= 0) {
            throw new IllegalArgumentException("invalid account lane");
        }
        this.laneId = laneId;
        this.queueCapacity = queueCapacity;
        this.localStateHash = computeStateHash();
        this.localFundsHash = computeFundsHash();
    }

    void bindOwner() {
        Thread current = Thread.currentThread();
        if (owner == null) owner = current;
        else if (owner != current) throw new IllegalStateException("account lane is bound to another thread");
    }

    void releaseOwnerForHandoff() {
        if (owner != null && owner != Thread.currentThread()) {
            throw new IllegalStateException("account lane owner mismatch");
        }
        owner = null;
    }

    void assertOwner() {
        bindOwner();
    }

    public int laneId() { return laneId; }
    public int queueCapacity() { return queueCapacity; }
    public long revision() { assertOwner(); return revision; }
    public long appliedSequence() { assertOwner(); return appliedSequence; }
    public long committedSequence() { assertOwner(); return committedSequence; }
    public long localStateHash() { assertOwner(); return localStateHash; }
    public long localFundsHash() { assertOwner(); return localFundsHash; }
    public boolean owns(long userId) { assertOwner(); return userIds.contains(userId); }
    public int userCount() { assertOwner(); return userIds.size(); }

    void registerUser(long userId) {
        assertOwner();
        if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
        userIds.add(userId);
    }

    void removeUser(long userId) {
        assertOwner();
        userIds.remove(userId);
    }

    void markPendingReservation(long orderId, long coreSequence) {
        assertOwner();
        if (orderId <= 0 || coreSequence <= committedSequence) {
            throw new IllegalArgumentException("invalid pending reservation identity");
        }
        if (pendingReservationSequences.containsKey(orderId)) {
            throw new IllegalStateException("reservation is already pending");
        }
        ReservationRuntime reservation = reservations.get(orderId);
        if (reservation == null) throw new IllegalStateException("pending reservation is missing");
        int nextUserCount = Math.addExact(pendingReservationCountsByUser.get(reservation.userId()), 1);
        int nextTotal = Math.addExact(totalPendingReservations, 1);
        IntLongHashMap unitsByAsset = pendingReservedUnitsByUser.get(reservation.userId());
        long previousUnits = unitsByAsset == null ? 0 : unitsByAsset.get(reservation.assetId());
        long nextUnits = Math.addExact(previousUnits, reservation.reservedUnits());
        pendingReservationSequences.put(orderId, coreSequence);
        if (unitsByAsset == null && nextUnits != 0) {
            unitsByAsset = new IntLongHashMap();
            pendingReservedUnitsByUser.put(reservation.userId(), unitsByAsset);
        }
        pendingReservationCountsByUser.put(reservation.userId(), nextUserCount);
        if (nextUnits != 0) unitsByAsset.put(reservation.assetId(), nextUnits);
        totalPendingReservations = nextTotal;
    }

    void completePendingReservation(long orderId, long coreSequence) {
        assertOwner();
        PendingReservationCompletion completion = pendingReservationCompletion(orderId, coreSequence);
        ReservationRuntime reservation = completion.reservation();
        pendingReservationSequences.removeKey(orderId);
        if (completion.nextUserCount() == 0) pendingReservationCountsByUser.removeKey(reservation.userId());
        else pendingReservationCountsByUser.put(reservation.userId(), completion.nextUserCount());
        if (completion.nextUnits() == 0) {
            if (completion.unitsByAsset() != null) {
                completion.unitsByAsset().removeKey(reservation.assetId());
                if (completion.unitsByAsset().isEmpty()) pendingReservedUnitsByUser.removeKey(reservation.userId());
            }
        } else {
            completion.unitsByAsset().put(reservation.assetId(), completion.nextUnits());
        }
        totalPendingReservations = completion.nextTotal();
    }

    void requirePendingReservationCompletion(long orderId, long coreSequence) {
        assertOwner();
        pendingReservationCompletion(orderId, coreSequence);
    }

    private PendingReservationCompletion pendingReservationCompletion(long orderId, long coreSequence) {
        long pendingSequence = pendingReservationSequences.getIfAbsent(orderId, 0);
        if (pendingSequence != coreSequence) {
            throw new IllegalStateException("pending reservation sequence mismatch");
        }
        ReservationRuntime reservation = reservations.get(orderId);
        if (reservation == null) throw new IllegalStateException("pending reservation is missing");
        int userCount = pendingReservationCountsByUser.get(reservation.userId());
        int nextUserCount = Math.subtractExact(userCount, 1);
        int nextTotal = Math.subtractExact(totalPendingReservations, 1);
        IntLongHashMap unitsByAsset = pendingReservedUnitsByUser.get(reservation.userId());
        long previousUnits = unitsByAsset == null ? 0 : unitsByAsset.get(reservation.assetId());
        long nextUnits = Math.subtractExact(previousUnits, reservation.reservedUnits());
        if (nextUserCount < 0 || nextTotal < 0 || nextUnits < 0) {
            throw new IllegalStateException("pending reservation counters are inconsistent");
        }
        if (nextUnits != 0 && unitsByAsset == null) {
            throw new IllegalStateException("pending reservation counters are inconsistent");
        }
        return new PendingReservationCompletion(reservation, nextUserCount, nextTotal, unitsByAsset, nextUnits);
    }

    void replacePendingReservation(ReservationRuntime previous, ReservationRuntime replacement) {
        assertOwner();
        if (!pendingReservationSequences.containsKey(previous.orderId())) return;
        if (previous.orderId() != replacement.orderId() || previous.userId() != replacement.userId()) {
            throw new IllegalStateException("pending reservation owner cannot change");
        }
        IntLongHashMap unitsByAsset = pendingReservedUnitsByUser.get(previous.userId());
        long currentUnits = unitsByAsset == null ? 0 : unitsByAsset.get(previous.assetId());
        long remainingUnits = Math.subtractExact(currentUnits, previous.reservedUnits());
        if (remainingUnits < 0) throw new IllegalStateException("pending reservation counters are inconsistent");
        if (previous.assetId() == replacement.assetId()) {
            long nextUnits = Math.addExact(remainingUnits, replacement.reservedUnits());
            if (nextUnits == 0) {
                if (unitsByAsset != null) {
                    unitsByAsset.removeKey(previous.assetId());
                    if (unitsByAsset.isEmpty()) pendingReservedUnitsByUser.removeKey(previous.userId());
                }
                return;
            }
            if (unitsByAsset == null) {
                unitsByAsset = new IntLongHashMap();
                pendingReservedUnitsByUser.put(previous.userId(), unitsByAsset);
            }
            unitsByAsset.put(previous.assetId(), nextUnits);
        } else {
            long existingReplacementUnits = unitsByAsset == null ? 0 : unitsByAsset.get(replacement.assetId());
            long replacementUnits = Math.addExact(existingReplacementUnits,
                    replacement.reservedUnits());
            if (unitsByAsset == null && replacementUnits != 0) {
                unitsByAsset = new IntLongHashMap();
                pendingReservedUnitsByUser.put(previous.userId(), unitsByAsset);
            }
            if (unitsByAsset != null) {
                if (remainingUnits == 0) unitsByAsset.removeKey(previous.assetId());
                else unitsByAsset.put(previous.assetId(), remainingUnits);
                if (replacementUnits == 0) unitsByAsset.removeKey(replacement.assetId());
                else unitsByAsset.put(replacement.assetId(), replacementUnits);
            }
        }
        if (unitsByAsset != null && unitsByAsset.isEmpty()) pendingReservedUnitsByUser.removeKey(previous.userId());
    }

    boolean pendingReservation(long orderId) {
        assertOwner();
        return pendingReservationSequences.containsKey(orderId);
    }

    long pendingReservedUnits(long userId, int assetId) {
        assertOwner();
        IntLongHashMap unitsByAsset = pendingReservedUnitsByUser.get(userId);
        return unitsByAsset == null ? 0 : unitsByAsset.get(assetId);
    }

    int pendingReservationCount(long userId) {
        assertOwner();
        return pendingReservationCountsByUser.get(userId);
    }

    int pendingReservationCount() {
        assertOwner();
        return totalPendingReservations;
    }

    boolean hasPendingReservations() {
        assertOwner();
        return totalPendingReservations != 0;
    }

    void applied(long coreSequence, long stateContribution, long fundsContribution) {
        assertOwner();
        requireApplySequence(coreSequence);
        appliedSequence = coreSequence;
        revision = Math.incrementExact(revision);
        localStateHash = transitionHash(localStateHash, coreSequence, stateContribution);
        localFundsHash = transitionHash(localFundsHash, coreSequence, fundsContribution);
    }

    void requireApplySequence(long coreSequence) {
        assertOwner();
        if (coreSequence <= appliedSequence) {
            throw new IllegalStateException("account lane apply is out of order");
        }
    }

    void committed(long coreSequence) {
        assertOwner();
        requireCommit(coreSequence);
        committedSequence = coreSequence;
    }

    void requireCommit(long coreSequence) {
        assertOwner();
        if (coreSequence < committedSequence || coreSequence > appliedSequence) {
            throw new IllegalStateException("account lane commit is out of order");
        }
    }

    void readFence(long coreSequence) {
        assertOwner();
        requireReadFence(coreSequence);
        advanceReadFence(coreSequence);
    }

    void requireReadFence(long coreSequence) {
        assertOwner();
        if (coreSequence < committedSequence || appliedSequence != committedSequence) {
            throw new IllegalStateException("account lane read fence crossed uncommitted work");
        }
    }

    void advanceReadFence(long coreSequence) {
        assertOwner();
        appliedSequence = coreSequence;
        committedSequence = coreSequence;
    }

    void restore(long revision, long appliedSequence, long committedSequence,
                 long localStateHash, long localFundsHash, LongHashSet restoredUsers) {
        assertOwner();
        if (revision < 0 || appliedSequence < committedSequence || committedSequence < 0
                || localStateHash == 0 || localFundsHash == 0 || restoredUsers == null) {
            throw new IllegalArgumentException("invalid account lane snapshot");
        }
        this.revision = revision;
        this.appliedSequence = appliedSequence;
        this.committedSequence = committedSequence;
        this.localStateHash = localStateHash;
        this.localFundsHash = localFundsHash;
        userIds.clear();
        userIds.addAll(restoredUsers);
        pendingReservationSequences.clear();
        pendingReservationCountsByUser.clear();
        pendingReservedUnitsByUser.clear();
        totalPendingReservations = 0;
    }

    LongHashSet userIdsSnapshot() {
        assertOwner();
        return new LongHashSet(userIds);
    }

    AccountLaneSnapshot snapshot(long fenceSequence) {
        assertOwner();
        requireSnapshot(fenceSequence);
        appliedSequence = fenceSequence;
        committedSequence = fenceSequence;
        java.util.List<Long> users = new java.util.ArrayList<>(userIds.size());
        userIds.forEach(users::add);
        users.sort(Long::compare);
        return new AccountLaneSnapshot(laneId, revision, appliedSequence, committedSequence,
                localStateHash, localFundsHash, users);
    }

    void requireSnapshot(long fenceSequence) {
        assertOwner();
        if (!pendingReservationSequences.isEmpty()) {
            throw new IllegalStateException("pending reservation cannot cross snapshot fence");
        }
        if (fenceSequence < committedSequence || appliedSequence != committedSequence) {
            throw new IllegalStateException("account lane snapshot crossed an incomplete commit");
        }
    }

    void restore(AccountLaneSnapshot snapshot) {
        assertOwner();
        if (snapshot == null || snapshot.laneId() != laneId) {
            throw new IllegalArgumentException("account lane snapshot route mismatch");
        }
        LongHashSet restoredUsers = new LongHashSet();
        snapshot.userIds().forEach(restoredUsers::add);
        restore(snapshot.revision(), snapshot.appliedSequence(), snapshot.committedSequence(),
                snapshot.localStateHash(), snapshot.localFundsHash(), restoredUsers);
    }

    private record PendingReservationCompletion(
            ReservationRuntime reservation, int nextUserCount, int nextTotal,
            IntLongHashMap unitsByAsset, long nextUnits) {
    }

    private static long mix(long hash, long value) {
        long mixed = hash;
        for (int shift = 0; shift < Long.SIZE; shift += Byte.SIZE) {
            mixed ^= (value >>> shift) & 0xff;
            mixed *= 0x100000001b3L;
        }
        return mixed;
    }

    private static long transitionHash(long current, long coreSequence, long contribution) {
        long hash = mix(current, coreSequence);
        hash = mix(hash, contribution);
        return hash == 0 ? 1 : hash;
    }

    private long computeStateHash() {
        long hash = mix(0xcbf29ce484222325L, laneId);
        long[] userKeys = userIds.toArray();
        java.util.Arrays.sort(userKeys);
        for (long userId : userKeys) {
            hash = mix(hash, userId);
            hash = mixText(hash, users.get(userId));
        }
        hash = mixMap(hash, orders);
        hash = mixMap(hash, reservations);
        hash = mixMap(hash, positions);
        hash = mixMap(hash, liquidations);
        hash = mixMap(hash, riskSnapshots);
        for (Map.Entry<CoreLeverageKey, Long> entry : leverages.entrySet()) {
            hash = mixText(hash, entry.getKey());
            hash = mix(hash, entry.getValue());
        }
        for (Map.Entry<Long, CoreAlgoOrderState> entry : algoOrders.entrySet()) {
            hash = mix(hash, entry.getKey());
            hash = mixText(hash, entry.getValue());
        }
        for (Map.Entry<Long, CoreTriggerOrderState> entry : triggerOrders.entrySet()) {
            hash = mix(hash, entry.getKey());
            hash = mixText(hash, entry.getValue());
        }
        return hash == 0 ? 1 : hash;
    }

    private long computeFundsHash() {
        long hash = mix(0xcbf29ce484222325L, laneId);
        long[] userKeys = balances.keySet().toArray();
        java.util.Arrays.sort(userKeys);
        for (long userId : userKeys) {
            hash = mix(hash, userId);
            IntObjectHashMap<BalanceRuntime> userBalances = balances.get(userId);
            int[] assetIds = userBalances.keySet().toArray();
            java.util.Arrays.sort(assetIds);
            for (int assetId : assetIds) {
                BalanceRuntime balance = userBalances.get(assetId);
                hash = mix(hash, assetId);
                hash = mix(hash, balance.availableUnits());
                hash = mix(hash, balance.lockedUnits());
            }
        }
        return hash == 0 ? 1 : hash;
    }

    void rebuildLocalHashes() {
        assertOwner();
        localStateHash = computeStateHash();
        localFundsHash = computeFundsHash();
    }

    private static <T> long mixMap(long hash, LongObjectHashMap<T> values) {
        long[] keys = values.keySet().toArray();
        java.util.Arrays.sort(keys);
        long mixed = hash;
        for (long key : keys) {
            mixed = mix(mixed, key);
            mixed = mixText(mixed, values.get(key));
        }
        return mixed;
    }

    private static long mixText(long hash, Object value) {
        byte[] bytes = String.valueOf(value).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        long mixed = mix(hash, bytes.length);
        for (byte item : bytes) {
            mixed ^= Byte.toUnsignedInt(item);
            mixed *= 0x100000001b3L;
        }
        return mixed;
    }
}
