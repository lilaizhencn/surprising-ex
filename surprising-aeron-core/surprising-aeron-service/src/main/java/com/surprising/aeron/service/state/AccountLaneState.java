package com.surprising.aeron.service.state;

import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;
import java.util.Map;
import java.util.TreeMap;
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
    final Map<CoreLeverageKey, Long> leverages = new TreeMap<>();
    final LongObjectHashMap<TreeSet<CoreLeverageKey>> leverageKeysByUser = new LongObjectHashMap<>();
    final Map<Long, CoreAlgoOrderState> algoOrders = new TreeMap<>();
    final Map<Long, CoreTriggerOrderState> triggerOrders = new TreeMap<>();
    final LongLongHashMap pendingReservationSequences = new LongLongHashMap();
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
    }

    void bindOwner() {
        Thread current = Thread.currentThread();
        if (owner == null) owner = current;
        else if (owner != current) throw new IllegalStateException("account lane is bound to another thread");
    }

    void releaseOwner() {
        if (owner != Thread.currentThread()) throw new IllegalStateException("account lane owner mismatch");
        balances.forEachValue(values -> values.forEachValue(BalanceRuntime::releaseOwnerForHandoff));
        owner = null;
    }

    void releaseOwnerForHandoff() {
        if (owner != null && owner != Thread.currentThread()) {
            throw new IllegalStateException("account lane owner mismatch");
        }
        balances.forEachValue(values -> values.forEachValue(BalanceRuntime::releaseOwnerForHandoff));
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
        pendingReservationSequences.put(orderId, coreSequence);
    }

    void completePendingReservation(long orderId, long coreSequence) {
        assertOwner();
        long pendingSequence = pendingReservationSequences.getIfAbsent(orderId, 0);
        if (pendingSequence != coreSequence) {
            throw new IllegalStateException("pending reservation sequence mismatch");
        }
        pendingReservationSequences.removeKey(orderId);
    }

    boolean pendingReservation(long orderId) {
        assertOwner();
        return pendingReservationSequences.containsKey(orderId);
    }

    long pendingReservedUnits(long userId, int assetId) {
        assertOwner();
        long[] total = {0};
        pendingReservationSequences.forEachKeyValue((orderId, ignored) -> {
            ReservationRuntime reservation = reservations.get(orderId);
            if (reservation != null && reservation.userId() == userId && reservation.assetId() == assetId) {
                total[0] = Math.addExact(total[0], reservation.reservedUnits());
            }
        });
        return total[0];
    }

    int pendingReservationCount(long userId) {
        assertOwner();
        int[] count = {0};
        pendingReservationSequences.forEachKeyValue((orderId, ignored) -> {
            ReservationRuntime reservation = reservations.get(orderId);
            if (reservation != null && reservation.userId() == userId) count[0]++;
        });
        return count[0];
    }

    boolean hasPendingReservations() {
        assertOwner();
        return !pendingReservationSequences.isEmpty();
    }

    void applied(long coreSequence, long userId, long stateContribution, long fundsContribution) {
        assertOwner();
        if (coreSequence < appliedSequence || userId <= 0 || !userIds.contains(userId)) {
            throw new IllegalStateException("account lane apply is out of order");
        }
        appliedSequence = coreSequence;
        revision = Math.incrementExact(revision);
        localStateHash = mix(mix(localStateHash, userId), stateContribution);
        localFundsHash = mix(mix(localFundsHash, userId), fundsContribution);
    }

    void committed(long coreSequence) {
        assertOwner();
        if (coreSequence < committedSequence || coreSequence > appliedSequence) {
            throw new IllegalStateException("account lane commit is out of order");
        }
        committedSequence = coreSequence;
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
    }

    LongHashSet userIdsSnapshot() {
        assertOwner();
        return new LongHashSet(userIds);
    }

    AccountLaneSnapshot snapshot(long fenceSequence, TradingCoreState laneState) {
        assertOwner();
        requireSnapshot(fenceSequence);
        appliedSequence = fenceSequence;
        committedSequence = fenceSequence;
        localStateHash = laneState.businessStateHash();
        localFundsHash = RollingFundsStateHash.compute(laneState);
        java.util.List<Long> users = new java.util.ArrayList<>(userIds.size());
        userIds.forEach(users::add);
        users.sort(Long::compare);
        return new AccountLaneSnapshot(laneId, revision, appliedSequence, committedSequence,
                localStateHash, localFundsHash, users, laneState);
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
        if (snapshot.state().businessStateHash() != snapshot.localStateHash()
                || RollingFundsStateHash.compute(snapshot.state()) != snapshot.localFundsHash()) {
            throw new IllegalArgumentException("account lane state hash mismatch");
        }
        restore(snapshot.revision(), snapshot.appliedSequence(), snapshot.committedSequence(),
                snapshot.localStateHash(), snapshot.localFundsHash(), restoredUsers);
    }

    private static long mix(long hash, long value) {
        long mixed = hash;
        for (int shift = 0; shift < Long.SIZE; shift += Byte.SIZE) {
            mixed ^= (value >>> shift) & 0xff;
            mixed *= 0x100000001b3L;
        }
        return mixed;
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
