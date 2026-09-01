package com.surprising.aeron.service.state;

import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.eclipse.collections.impl.set.mutable.primitive.IntHashSet;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreMatcherTransition;
import com.surprising.aeron.protocol.CoreRiskScanControlView;
import com.surprising.aeron.service.matching.CoreMatchingResult;
import com.surprising.product.api.ProductLine;
import java.util.Collections;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public final class TradingRuntimeState implements AutoCloseable {

    public static final int MAX_PENDING_TRANSFERS = 131_072;
    private static final int CHANGE_KEY_COMPACTION_THRESHOLD = 512;

    private ProductLine productLine = ProductLine.LINEAR_PERPETUAL;
    private long revision;
    private final LaneTopology topology;
    private final AccountLaneState[] accountLanes;
    private final org.eclipse.collections.impl.list.mutable.primitive.LongArrayList[] laneUserScratch;
    private final Object[] laneMutationResultsScratch;
    private final int[] accountLaneQueueHighWaterMarks;
    private final long[][] accountLaneCompletedOperations;
    private final long[][] accountLaneLatencySamples;
    private final long[][] accountLaneTotalLatencyNanos;
    private final long[][] accountLaneMaxLatencyNanos;
    private boolean accountLanesStarted;

    private final IntObjectHashMap<MarkPriceRuntime> markPrices = new IntObjectHashMap<>();
    private final IntObjectHashMap<RiskScanRuntime> riskScans = new IntObjectHashMap<>();
    private final TreasuryRuntime treasury = new TreasuryRuntime();
    private final Map<String, CoreInstrumentState> instruments = new TreeMap<>();
    private final Map<CoreCancelAllAfterKey, CoreCancelAllAfterState> cancelAllAfterTimers = new TreeMap<>();
    private final Map<Long, CoreFeePolicyState> feePolicies = new TreeMap<>();
    private final Map<Long, TransferRuntime> pendingTransfers = new TreeMap<>();
    private final LongObjectHashMap<LongHashSet> pendingReservationsBySequence = new LongObjectHashMap<>();
    private final LongLongHashMap pendingReservationUsers = new LongLongHashMap();
    private final LongLongHashMap orderLaneIds = new LongLongHashMap();
    private final LongLongHashMap reservationLaneIds = new LongLongHashMap();
    private final LongLongHashMap positionLaneIds = new LongLongHashMap();
    private final LongObjectHashMap<UserRuntime> publishedUsers = new LongObjectHashMap<>();
    private final LongObjectHashMap<OrderRuntime> publishedOrders = new LongObjectHashMap<>();
    private final LongObjectHashMap<ReservationRuntime> publishedReservations = new LongObjectHashMap<>();
    private final LongObjectHashMap<PositionRuntime> publishedPositions = new LongObjectHashMap<>();
    private final PublishedLaneChanges[] publishedLaneChanges;
    private int totalPendingReservations;
    private long nextLiquidationId = 1;
    private CoreRiskScanControlView riskScanControl = CoreRiskState.defaultScanControl();
    private final LongHashSet changedUsers = new LongHashSet();
    private final LongObjectHashMap<IntHashSet> changedBalances = new LongObjectHashMap<>();
    private final LongHashSet changedOrders = new LongHashSet();
    private final LongHashSet changedReservations = new LongHashSet();
    private final LongHashSet changedPositions = new LongHashSet();
    private final LongHashSet changedLiquidations = new LongHashSet();
    private final IntHashSet changedMarkPrices = new IntHashSet();
    private final LongHashSet changedRiskSnapshots = new LongHashSet();
    private final IntHashSet changedRiskScans = new IntHashSet();
    private final LongHashSet changedClientOrders = new LongHashSet();
    private final LongObjectHashMap<LongHashSet> changedClientOrdersByUser = new LongObjectHashMap<>();
    private final TreeSet<String> changedInstruments = new TreeSet<>();
    private final TreeSet<CoreLeverageKey> changedLeverages = new TreeSet<>();
    private final LongHashSet changedAlgoOrders = new LongHashSet();
    private final TreeSet<CoreCancelAllAfterKey> changedCancelAllAfterTimers = new TreeSet<>();
    private final LongHashSet changedTriggerOrders = new LongHashSet();
    private final LongHashSet changedFeePolicies = new LongHashSet();
    private final LongObjectHashMap<UserRuntime>[] patchUsersBeforeByLane;
    private final LaneBalancePatches[] patchBalancesBeforeByLane;
    private final LongObjectHashMap<PatchReservationBefore>[] patchReservationsBeforeByLane;
    private final LongObjectHashMap<PatchOrderBefore>[] patchOrdersBeforeByLane;
    private final RuntimeCommitPatch.Builder activePatchBuilder = RuntimeCommitPatch.builder(productLine);
    private boolean orderBatchMutationScope;
    private ConcurrentHashMap<Long, PatchBefore<LiquidationRuntime>> patchLiquidationsBefore =
            new ConcurrentHashMap<>();
    private ConcurrentHashMap<Long, PatchBefore<RiskSnapshotRuntime>> patchRiskSnapshotsBefore =
            new ConcurrentHashMap<>();
    private ConcurrentHashMap<CoreLeverageKey, PatchBefore<Long>> patchLeveragesBefore =
            new ConcurrentHashMap<>();
    private ConcurrentHashMap<Long, PatchBefore<CoreAlgoOrderState>> patchAlgoOrdersBefore =
            new ConcurrentHashMap<>();
    private ConcurrentHashMap<Long, PatchBefore<CoreTriggerOrderState>> patchTriggerOrdersBefore =
            new ConcurrentHashMap<>();
    private ConcurrentHashMap<RuntimeCommitPatch.ClientOrderKey, PatchBefore<Long>> patchClientOrdersBefore =
            new ConcurrentHashMap<>();
    private ConcurrentHashMap<CoreCancelAllAfterKey, PatchBefore<CoreCancelAllAfterState>> patchTimersBefore =
            new ConcurrentHashMap<>();
    private ConcurrentHashMap<Integer, PatchBefore<MarkPriceRuntime>> patchMarkPricesBefore =
            new ConcurrentHashMap<>();
    private ConcurrentHashMap<Integer, PatchBefore<RiskScanRuntime>> patchRiskScansBefore =
            new ConcurrentHashMap<>();
    private ConcurrentHashMap<String, PatchBefore<CoreInstrumentState>> patchInstrumentsBefore =
            new ConcurrentHashMap<>();
    private ConcurrentHashMap<Long, PatchBefore<TransferRuntime>> patchPendingTransfersBefore =
            new ConcurrentHashMap<>();
    private ConcurrentHashMap<Long, PatchBefore<CoreFeePolicyState>> patchFeePoliciesBefore =
            new ConcurrentHashMap<>();
    private long patchNextLiquidationIdBefore;
    private boolean patchNextLiquidationIdChanged;
    private CoreRiskScanControlView patchRiskScanControlBefore;
    private boolean patchRiskScanControlChanged;
    private final ThreadLocal<AccountLaneState> laneCommandScope = new ThreadLocal<>();
    private final RuntimePerpetualMatchProcessor.BatchValidationScratch perpetualBatchValidationScratch =
            new RuntimePerpetualMatchProcessor.BatchValidationScratch();
    private Thread owner;

    public TradingRuntimeState() {
        this(LaneTopology.configured(Boolean.getBoolean("surprising.aeron.p10-characterization")));
    }

    public TradingRuntimeState(LaneTopology topology) {
        if (topology == null) throw new IllegalArgumentException("lane topology is required");
        this.topology = topology;
        this.accountLanes = new AccountLaneState[topology.accountLaneCount()];
        @SuppressWarnings("unchecked")
        LongObjectHashMap<UserRuntime>[] userPatches =
                (LongObjectHashMap<UserRuntime>[]) new LongObjectHashMap<?>[
                        topology.accountLaneCount()];
        this.patchUsersBeforeByLane = userPatches;
        this.patchBalancesBeforeByLane = new LaneBalancePatches[topology.accountLaneCount()];
        @SuppressWarnings("unchecked")
        LongObjectHashMap<PatchReservationBefore>[] reservationPatches =
                (LongObjectHashMap<PatchReservationBefore>[]) new LongObjectHashMap<?>[topology.accountLaneCount()];
        this.patchReservationsBeforeByLane = reservationPatches;
        @SuppressWarnings("unchecked")
        LongObjectHashMap<PatchOrderBefore>[] orderPatches =
                (LongObjectHashMap<PatchOrderBefore>[]) new LongObjectHashMap<?>[topology.accountLaneCount()];
        this.patchOrdersBeforeByLane = orderPatches;
        this.publishedLaneChanges = new PublishedLaneChanges[topology.accountLaneCount()];
        org.eclipse.collections.impl.list.mutable.primitive.LongArrayList[] routedUsers =
                new org.eclipse.collections.impl.list.mutable.primitive.LongArrayList[topology.accountLaneCount()];
        this.laneUserScratch = routedUsers;
        this.laneMutationResultsScratch = new Object[topology.accountLaneCount()];
        this.accountLaneQueueHighWaterMarks = new int[topology.accountLaneCount()];
        this.accountLaneCompletedOperations = laneMetricValues(topology.accountLaneCount());
        this.accountLaneLatencySamples = laneMetricValues(topology.accountLaneCount());
        this.accountLaneTotalLatencyNanos = laneMetricValues(topology.accountLaneCount());
        this.accountLaneMaxLatencyNanos = laneMetricValues(topology.accountLaneCount());
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            accountLanes[laneId] = new AccountLaneState(laneId, topology.accountLaneQueueCapacity());
            laneUserScratch[laneId] = new org.eclipse.collections.impl.list.mutable.primitive.LongArrayList(4);
            patchUsersBeforeByLane[laneId] = new LongObjectHashMap<>();
            patchBalancesBeforeByLane[laneId] = new LaneBalancePatches();
            patchReservationsBeforeByLane[laneId] = new LongObjectHashMap<>();
            patchOrdersBeforeByLane[laneId] = new LongObjectHashMap<>();
            publishedLaneChanges[laneId] = new PublishedLaneChanges();
        }
    }

    public LaneTopology topology() {
        return topology;
    }

    public AccountLaneView accountLane(long userId) {
        assertOwner();
        return accountLaneById(topology.accountLaneId(userId));
    }

    public AccountLaneView[] accountLanes() {
        assertOwner();
        AccountLaneView[] views = new AccountLaneView[accountLanes.length];
        for (int laneId = 0; laneId < views.length; laneId++) views[laneId] = accountLaneById(laneId);
        return views;
    }

    public AccountLaneView accountLaneById(int laneId) {
        assertOwner();
        if (laneId < 0 || laneId >= accountLanes.length) throw new IllegalArgumentException("invalid laneId");
        return onLane(laneId, lane -> laneView(laneId, lane));
    }

    public long accountLaneLocalStateHashById(int laneId) {
        assertOwner();
        if (laneId < 0 || laneId >= accountLanes.length) {
            throw new IllegalArgumentException("invalid laneId");
        }
        AccountLaneState lane = accountLanes[laneId];
        lane.assertOwner();
        return lane.localStateHash();
    }

    public long accountLaneLocalFundsHashById(int laneId) {
        assertOwner();
        if (laneId < 0 || laneId >= accountLanes.length) {
            throw new IllegalArgumentException("invalid laneId");
        }
        AccountLaneState lane = accountLanes[laneId];
        lane.assertOwner();
        return lane.localFundsHash();
    }

    public AccountLaneMetricsSnapshot accountLaneMetricsById(int laneId) {
        assertOwner();
        if (laneId < 0 || laneId >= accountLanes.length) throw new IllegalArgumentException("invalid laneId");
        return new AccountLaneMetricsSnapshot(0, accountLanes[laneId].queueCapacity(),
                accountLaneQueueHighWaterMarks[laneId], 0, 0,
                accountLaneCompletedOperations[laneId], accountLaneLatencySamples[laneId],
                accountLaneTotalLatencyNanos[laneId], accountLaneMaxLatencyNanos[laneId]);
    }

    public void startAccountLanes() {
        assertOwner();
        if (accountLanesStarted) throw new IllegalStateException("account lanes are already started");
        for (AccountLaneState lane : accountLanes) lane.bindOwner();
        accountLanesStarted = true;
    }

    public void readFence(long userId, long committedCoreSequence) {
        assertOwner();
        onLane(topology.accountLaneId(userId), lane -> {
            lane.readFence(committedCoreSequence);
            return null;
        });
    }

    public void readFenceAll(long committedCoreSequence) {
        assertOwner();
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            final int currentLaneId = laneId;
            onLane(currentLaneId, lane -> {
                lane.requireReadFence(committedCoreSequence);
                return null;
            });
        }
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            final int currentLaneId = laneId;
            onLane(currentLaneId, lane -> {
                lane.advanceReadFence(committedCoreSequence);
                return null;
            });
        }
    }

    private static AccountLaneView laneView(int laneId, AccountLaneState lane) {
        String ownerThreadName = Thread.currentThread().getName();
        if (ownerThreadName.isBlank()) ownerThreadName = "product-core-owner";
        return new AccountLaneView(laneId, lane.revision(), lane.appliedSequence(), lane.committedSequence(),
                lane.localStateHash(), lane.localFundsHash(), lane.userCount(),
                0, lane.queueCapacity(), 0, ownerThreadName);
    }

    private <T> T onLane(long userId, LaneOperation<T> operation) {
        return onLane(topology.accountLaneId(userId), operation);
    }

    private <T> T onLane(int laneId, LaneOperation<T> operation) {
        AccountLaneState scoped = laneCommandScope.get();
        if (scoped != null) {
            if (scoped.laneId() != laneId) {
                throw new IllegalStateException("account lane command crossed its owner boundary");
            }
            return operation.apply(scoped);
        }
        return operation.apply(accountLanes[laneId]);
    }

    <T> T inLaneCommandScope(AccountLaneState lane, LaneOperation<T> operation) {
        if (lane == null || operation == null || laneCommandScope.get() != null) {
            throw new IllegalStateException("invalid account lane command scope");
        }
        lane.assertOwner();
        laneCommandScope.set(lane);
        try {
            return operation.apply(lane);
        } finally {
            laneCommandScope.remove();
            if (Thread.currentThread() == owner) flushPublishedChanges(lane.laneId());
        }
    }

    public <T> T executeUserSettlement(long userId, java.util.function.Supplier<T> operation) {
        assertOwner();
        if (userId <= 0 || operation == null) {
            throw new IllegalArgumentException("invalid user settlement command");
        }
        int laneId = topology.accountLaneId(userId);
        executeLaneMutations(1L << laneId, 1, false, ignored -> operation.get());
        @SuppressWarnings("unchecked") T result = (T) laneMutationResultsScratch[laneId];
        return result;
    }

    public <T> T executeUserRisk(long userId, java.util.function.Supplier<T> operation) {
        assertOwner();
        if (userId <= 0 || operation == null) {
            throw new IllegalArgumentException("invalid user risk command");
        }
        int laneId = topology.accountLaneId(userId);
        executeLaneMutations(1L << laneId, 1, false, ignored -> operation.get());
        @SuppressWarnings("unchecked") T result = (T) laneMutationResultsScratch[laneId];
        return result;
    }

    public Object[] executeOwnerSettlements(Iterable<Long> userIds,
                                            java.util.function.IntFunction<Object> operation) {
        return executeOwnerSettlements(userIds, Long::longValue, operation);
    }

    public <E> Object[] executeOwnerSettlements(Iterable<E> values,
                                                java.util.function.ToLongFunction<E> ownerUserId,
                                                java.util.function.IntFunction<Object> operation) {
        assertOwner();
        if (values == null || ownerUserId == null || operation == null) {
            throw new IllegalArgumentException("invalid owner settlement command");
        }
        long selectedLaneMask = 0;
        for (E value : values) {
            if (value == null) continue;
            long userId = ownerUserId.applyAsLong(value);
            if (userId > 0) selectedLaneMask |= topology.accountLaneMask(userId);
        }
        return executeLaneMutations(selectedLaneMask, Long.bitCount(selectedLaneMask), false, operation);
    }

    public <E> Object[] executeLifecycleSettlements(Iterable<E> values,
                                                    java.util.function.ToLongFunction<E> ownerUserId,
                                                    java.util.function.IntFunction<Object> operation) {
        assertOwner();
        if (values == null || ownerUserId == null || operation == null) {
            throw new IllegalArgumentException("invalid lifecycle settlement command");
        }
        long selectedLaneMask = 0;
        int workItems = 0;
        for (E value : values) {
            if (value == null) continue;
            long userId = ownerUserId.applyAsLong(value);
            if (userId <= 0) continue;
            workItems++;
            selectedLaneMask |= topology.accountLaneMask(userId);
        }
        return executeLaneMutations(selectedLaneMask, workItems, true, operation);
    }

    private Object[] executeLaneMutations(long selectedLaneMask, int workItems, boolean allowParallel,
                                          java.util.function.IntFunction<Object> operation) {
        assertOwner();
        long validMask = accountLanes.length == Long.SIZE ? -1L : (1L << accountLanes.length) - 1L;
        if ((selectedLaneMask & ~validMask) != 0 || workItems < 0 || operation == null) {
            throw new IllegalArgumentException("invalid account lane mutation");
        }
        Object[] results = laneMutationResultsScratch;
        java.util.Arrays.fill(results, null);
        if (selectedLaneMask == 0) {
            return results;
        }
        if (workItems == 0) throw new IllegalArgumentException("account lane mutation has no work");
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            if ((selectedLaneMask & 1L << laneId) == 0) continue;
            int currentLaneId = laneId;
            long startedNanos = System.nanoTime();
            results[laneId] = inLaneCommandScope(accountLanes[laneId],
                    ignored -> operation.apply(currentLaneId));
            recordLaneOperation(laneId, AccountLaneOperationType.SETTLEMENT,
                    System.nanoTime() - startedNanos);
        }
        return results;
    }

    @FunctionalInterface
    interface LaneOperation<T> {
        T apply(AccountLaneState lane);
    }

    public boolean currentLaneOwns(long userId) {
        AccountLaneState scoped = laneCommandScope.get();
        if (scoped == null) throw new IllegalStateException("account lane command scope is required");
        return topology.accountLaneId(userId) == scoped.laneId();
    }

    private static void applyLaneUsers(AccountLaneState lane,
                                       org.eclipse.collections.impl.list.mutable.primitive.LongArrayList users,
                                       long coreSequence,
                                       long stateContribution, long fundsContribution) {
        org.eclipse.collections.api.iterator.LongIterator iterator = users.longIterator();
        while (iterator.hasNext()) {
            long userId = iterator.next();
            if (!lane.owns(userId)) lane.registerUser(userId);
        }
        lane.applied(coreSequence, stateContribution, fundsContribution);
    }

    private static BalanceRuntime copyBalance(IntObjectHashMap<BalanceRuntime> balances, int assetId) {
        BalanceRuntime balance = balances == null ? null : balances.get(assetId);
        return balance == null ? null : new BalanceRuntime(balance.userId(), balance.assetId(),
                balance.availableUnits(), balance.lockedUnits());
    }

    private static IntObjectHashMap<BalanceRuntime> copyBalances(IntObjectHashMap<BalanceRuntime> balances) {
        if (balances == null) return null;
        IntObjectHashMap<BalanceRuntime> copy = new IntObjectHashMap<>(balances.size());
        balances.forEachKeyValue((assetId, balance) -> copy.put(assetId,
                new BalanceRuntime(balance.userId(), balance.assetId(),
                        balance.availableUnits(), balance.lockedUnits())));
        return copy;
    }

    private static LongObjectHashMap<IntObjectHashMap<BalanceRuntime>> copyAllBalances(
            LongObjectHashMap<IntObjectHashMap<BalanceRuntime>> balances) {
        LongObjectHashMap<IntObjectHashMap<BalanceRuntime>> copy = new LongObjectHashMap<>(balances.size());
        balances.forEachKeyValue((userId, userBalances) -> copy.put(userId, copyBalances(userBalances)));
        return copy;
    }

    private static LongObjectHashMap<LongObjectHashMap<Long>> copyClientOrderIndex(
            LongObjectHashMap<LongObjectHashMap<Long>> index) {
        LongObjectHashMap<LongObjectHashMap<Long>> copy = new LongObjectHashMap<>(index.size());
        index.forEachKeyValue((userId, values) -> copy.put(userId, new LongObjectHashMap<>(values)));
        return copy;
    }

    private void publishUser(long userId, UserRuntime value) {
        AccountLaneState scoped = laneCommandScope.get();
        if (scoped == null) putOrRemove(publishedUsers, userId, value);
        else publishedLaneChanges[scoped.laneId()].putUser(userId, value);
    }

    private void publishOrder(long orderId, OrderRuntime value) {
        AccountLaneState scoped = laneCommandScope.get();
        if (scoped == null) putOrRemove(publishedOrders, orderId, value);
        else publishedLaneChanges[scoped.laneId()].putOrder(orderId, value);
    }

    private void publishReservation(long orderId, ReservationRuntime value) {
        AccountLaneState scoped = laneCommandScope.get();
        if (scoped == null) putOrRemove(publishedReservations, orderId, value);
        else publishedLaneChanges[scoped.laneId()].putReservation(orderId, value);
    }

    private void publishPosition(long positionKey, PositionRuntime value) {
        AccountLaneState scoped = laneCommandScope.get();
        if (scoped == null) putOrRemove(publishedPositions, positionKey, value);
        else publishedLaneChanges[scoped.laneId()].putPosition(positionKey, value);
    }

    private void flushPublishedChanges(int laneId) {
        publishedLaneChanges[laneId].drainTo(
                laneId, publishedUsers, publishedOrders, publishedReservations, publishedPositions,
                orderLaneIds, reservationLaneIds, positionLaneIds);
    }

    private static <V> void putOrRemove(LongObjectHashMap<V> values, long key, V value) {
        if (value == null) values.remove(key); else values.put(key, value);
    }

    private static final class PublishedLaneChanges {
        private final LongObjectHashMap<UserRuntime> users = new LongObjectHashMap<>();
        private final LongHashSet removedUsers = new LongHashSet();
        private final LongObjectHashMap<OrderRuntime> orders = new LongObjectHashMap<>();
        private final LongHashSet removedOrders = new LongHashSet();
        private final LongObjectHashMap<ReservationRuntime> reservations = new LongObjectHashMap<>();
        private final LongHashSet removedReservations = new LongHashSet();
        private final LongObjectHashMap<PositionRuntime> positions = new LongObjectHashMap<>();
        private final LongHashSet removedPositions = new LongHashSet();

        private void putUser(long key, UserRuntime value) {
            update(users, removedUsers, key, value);
        }

        private void putOrder(long key, OrderRuntime value) {
            update(orders, removedOrders, key, value);
        }

        private void putReservation(long key, ReservationRuntime value) {
            update(reservations, removedReservations, key, value);
        }

        private void putPosition(long key, PositionRuntime value) {
            update(positions, removedPositions, key, value);
        }

        private void drainTo(int laneId,
                             LongObjectHashMap<UserRuntime> targetUsers,
                             LongObjectHashMap<OrderRuntime> targetOrders,
                             LongObjectHashMap<ReservationRuntime> targetReservations,
                             LongObjectHashMap<PositionRuntime> targetPositions,
                             LongLongHashMap targetOrderLanes,
                             LongLongHashMap targetReservationLanes,
                             LongLongHashMap targetPositionLanes) {
            drain(users, removedUsers, targetUsers);
            drainIndexed(orders, removedOrders, targetOrders, targetOrderLanes, laneId);
            drainIndexed(reservations, removedReservations, targetReservations, targetReservationLanes, laneId);
            drainIndexed(positions, removedPositions, targetPositions, targetPositionLanes, laneId);
        }

        private static <V> void update(LongObjectHashMap<V> values, LongHashSet removed, long key, V value) {
            if (value == null) {
                values.remove(key);
                removed.add(key);
            } else {
                removed.remove(key);
                values.put(key, value);
            }
        }

        private static <V> void drain(LongObjectHashMap<V> values, LongHashSet removed,
                                      LongObjectHashMap<V> target) {
            removed.forEach(target::remove);
            values.forEachKeyValue(target::put);
            removed.clear();
            values.clear();
        }

        private static <V> void drainIndexed(LongObjectHashMap<V> values, LongHashSet removed,
                                             LongObjectHashMap<V> target, LongLongHashMap targetLanes,
                                             int laneId) {
            removed.forEach(key -> {
                target.remove(key);
                targetLanes.removeKey(key);
            });
            values.forEachKeyValue((key, value) -> {
                target.put(key, value);
                targetLanes.put(key, laneId + 1L);
            });
            removed.clear();
            values.clear();
        }
    }

    @Override
    public void close() {
        accountLanesStarted = false;
    }

    private static long[][] laneMetricValues(int laneCount) {
        long[][] values = new long[laneCount][];
        for (int laneId = 0; laneId < laneCount; laneId++) {
            values[laneId] = new long[AccountLaneOperationType.values().length];
        }
        return values;
    }

    private void recordLaneOperation(int laneId, AccountLaneOperationType operation, long latencyNanos) {
        int operationIndex = operation.ordinal();
        accountLaneCompletedOperations[laneId][operationIndex]++;
        accountLaneLatencySamples[laneId][operationIndex]++;
        accountLaneTotalLatencyNanos[laneId][operationIndex] = Math.addExact(
                accountLaneTotalLatencyNanos[laneId][operationIndex], latencyNanos);
        accountLaneMaxLatencyNanos[laneId][operationIndex] = Math.max(
                accountLaneMaxLatencyNanos[laneId][operationIndex], latencyNanos);
    }

    private record PendingReservationCompletion(ReservationRuntime reservation, LongHashSet clientKeys) {
        private PendingReservationCompletion {
            if (clientKeys == null) throw new IllegalArgumentException("client keys are required");
        }
    }

    private record PendingReservationRef(long orderId, long userId) {
    }

    private record PendingReservationBatchCompletion(
            long orderId, long userId, ReservationRuntime reservation, LongHashSet clientKeys) {
        private PendingReservationBatchCompletion {
            if (orderId <= 0 || userId <= 0 || clientKeys == null) {
                throw new IllegalArgumentException("invalid pending reservation completion");
            }
        }
    }

    private record TerminalRelease(long units, int assetId) {
    }

    private record CanceledOrder(OrderRuntime order, ReservationRuntime reservation) {
    }

    private record ReservedOrder(OrderRuntime order, ReservationRuntime reservation) {
    }

    private record TerminalOrderPrune(long orderId, long userId, long clientKey) {
    }

    private record TerminalOrderPruned(
            long orderId, long userId, int reservationAssetId, boolean reservationRemoved,
            long clientKey, boolean clientRemoved) {
    }

    public void markPendingReservation(long userId, long orderId, long coreSequence) {
        assertOwner();
        captureReservationBefore(orderId);
        if (pendingReservationUsers.containsKey(orderId)) {
            throw new IllegalStateException("reservation is already indexed as pending");
        }
        int nextTotalPendingReservations = Math.addExact(totalPendingReservations, 1);
        onLane(userId, lane -> {
            ReservationRuntime reservation = lane.reservations.get(orderId);
            if (reservation == null || reservation.userId() != userId) {
                throw new IllegalStateException("pending reservation is missing");
            }
            lane.markPendingReservation(orderId, coreSequence);
            return null;
        });
        pendingReservationsBySequence.getIfAbsentPut(coreSequence, LongHashSet::new).add(orderId);
        pendingReservationUsers.put(orderId, userId);
        totalPendingReservations = nextTotalPendingReservations;
    }

    public void completePendingReservation(long userId, long orderId, long coreSequence) {
        assertOwner();
        captureUserBefore(userId);
        captureOrderBefore(orderId);
        captureReservationBefore(orderId);
        int nextTotalPendingReservations = Math.subtractExact(totalPendingReservations, 1);
        if (nextTotalPendingReservations < 0) {
            throw new IllegalStateException("pending reservation counters are inconsistent");
        }
        requirePendingReservationIndex(orderId, coreSequence, userId);
        PendingReservationCompletion completion = onLane(userId, accountLane -> {
            ReservationRuntime reservation = accountLane.reservations.get(orderId);
            LongHashSet clientKeys = clientKeysForOrder(accountLane, orderId);
            if (reservation != null) captureBalanceBefore(userId, reservation.assetId());
            clientKeys.forEach(clientKey -> captureClientOrderBefore(userId, clientKey));
            accountLane.completePendingReservation(orderId, coreSequence);
            return new PendingReservationCompletion(reservation, clientKeys);
        });
        changedOrders.add(orderId);
        changedReservations.add(orderId);
        changedUsers.add(userId);
        if (completion.reservation() != null) changedBalance(userId, completion.reservation().assetId());
        completion.clientKeys().forEach(clientKey -> {
            changedClientOrders.add(clientKey);
            changedClientOrder(userId, clientKey);
        });
        unindexPendingReservation(orderId, coreSequence, userId, nextTotalPendingReservations);
    }

    public void completePendingReservations(long coreSequence) {
        assertOwner();
        if (coreSequence <= 0) throw new IllegalArgumentException("coreSequence must be positive");
        LongHashSet pending = pendingReservationsBySequence.get(coreSequence);
        if (pending == null) return;
        List<PendingReservationRef> refs = new ArrayList<>(pending.size());
        for (long orderId : pending.toArray()) {
            long userId = pendingReservationUsers.getIfAbsent(orderId, 0);
            if (userId == 0) throw new IllegalStateException("pending reservation owner is missing");
            refs.add(new PendingReservationRef(orderId, userId));
        }
        List<PendingReservationBatchCompletion> completions = preflightPendingReservationCompletions(
                coreSequence, refs);
        executeOwnerSettlements(completions, PendingReservationBatchCompletion::userId, laneId -> {
            AccountLaneState lane = laneCommandScope.get();
            for (PendingReservationBatchCompletion completion : completions) {
                if (topology.accountLaneId(completion.userId()) != laneId) continue;
                lane.completePendingReservation(completion.orderId(), coreSequence);
            }
            return null;
        });
        for (PendingReservationBatchCompletion completion : completions) {
            changedOrders.add(completion.orderId());
            changedReservations.add(completion.orderId());
            changedUsers.add(completion.userId());
            changedBalance(completion.userId(), completion.reservation().assetId());
            completion.clientKeys().forEach(clientKey -> {
                changedClientOrders.add(clientKey);
                changedClientOrder(completion.userId(), clientKey);
            });
            int nextTotalPendingReservations = Math.subtractExact(totalPendingReservations, 1);
            unindexPendingReservation(completion.orderId(), coreSequence, completion.userId(),
                    nextTotalPendingReservations);
        }
    }

    private List<PendingReservationBatchCompletion> preflightPendingReservationCompletions(
            long coreSequence, List<PendingReservationRef> refs) {
        int remainingPendingReservations = totalPendingReservations;
        List<PendingReservationBatchCompletion> completions = new ArrayList<>(refs.size());
        for (PendingReservationRef ref : refs) {
            captureUserBefore(ref.userId());
            captureOrderBefore(ref.orderId());
            captureReservationBefore(ref.orderId());
            requirePendingReservationIndex(ref.orderId(), coreSequence, ref.userId());
            PendingReservationBatchCompletion completion = onLane(ref.userId(), lane -> {
                ReservationRuntime reservation = lane.reservations.get(ref.orderId());
                LongHashSet clientKeys = clientKeysForOrder(lane, ref.orderId());
                if (reservation != null) captureBalanceBefore(ref.userId(), reservation.assetId());
                clientKeys.forEach(clientKey -> captureClientOrderBefore(ref.userId(), clientKey));
                lane.requirePendingReservationCompletion(ref.orderId(), coreSequence);
                return new PendingReservationBatchCompletion(
                        ref.orderId(), ref.userId(), reservation, clientKeys);
            });
            remainingPendingReservations = Math.subtractExact(remainingPendingReservations, 1);
            if (remainingPendingReservations < 0) {
                throw new IllegalStateException("pending reservation counters are inconsistent");
            }
            completions.add(completion);
        }
        return List.copyOf(completions);
    }

    private void requirePendingReservationIndex(long orderId, long coreSequence, long userId) {
        long indexedUserId = pendingReservationUsers.getIfAbsent(orderId, 0);
        LongHashSet orderIds = pendingReservationsBySequence.get(coreSequence);
        if (indexedUserId != userId || orderIds == null || !orderIds.contains(orderId)) {
            throw new IllegalStateException("pending reservation index differs from account lane state");
        }
    }

    private void unindexPendingReservation(long orderId, long coreSequence, long userId,
                                           int nextTotalPendingReservations) {
        requirePendingReservationIndex(orderId, coreSequence, userId);
        LongHashSet orderIds = pendingReservationsBySequence.get(coreSequence);
        orderIds.remove(orderId);
        pendingReservationUsers.removeKey(orderId);
        if (orderIds.isEmpty()) pendingReservationsBySequence.removeKey(coreSequence);
        totalPendingReservations = nextTotalPendingReservations;
    }

    boolean pendingReservation(long orderId, long userId) {
        assertOwner();
        return onLane(userId, lane -> lane.pendingReservation(orderId));
    }

    long pendingReservedUnits(long userId, int assetId) {
        assertOwner();
        return onLane(userId, lane -> lane.pendingReservedUnits(userId, assetId));
    }

    int pendingReservationCount(long userId) {
        assertOwner();
        return onLane(userId, lane -> lane.pendingReservationCount(userId));
    }

    int pendingReservationCount() {
        assertOwner();
        return totalPendingReservations;
    }

    public boolean hasPendingReservations() {
        assertOwner();
        return totalPendingReservations != 0;
    }

    private void assertPendingReservationCounts() {
        int lanePendingReservations = 0;
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            int currentLaneId = laneId;
            lanePendingReservations = Math.addExact(lanePendingReservations,
                    onLane(currentLaneId, AccountLaneState::pendingReservationCount));
        }
        if (lanePendingReservations != totalPendingReservations) {
            throw new IllegalStateException("pending reservation counters differ from account lanes");
        }
    }

    public long stageLaneMutation(
            long coreSequence, Iterable<Long> userIds,
            long stateContribution, long fundsContribution) {
        assertOwner();
        if (coreSequence <= 0 || userIds == null) {
            throw new IllegalArgumentException("invalid lane apply");
        }
        clearLaneUserScratch();
        for (Long userId : userIds) {
            if (userId == null || userId <= 0) continue;
            addLaneUser(userId.longValue());
        }
        return stageLaneMutationFromScratch(coreSequence, stateContribution, fundsContribution);
    }

    public long stageLaneMutation(
            long coreSequence, long[] userIds,
            long stateContribution, long fundsContribution) {
        assertOwner();
        if (coreSequence <= 0 || userIds == null) {
            throw new IllegalArgumentException("invalid lane apply");
        }
        clearLaneUserScratch();
        for (long userId : userIds) {
            if (userId > 0) addLaneUser(userId);
        }
        return stageLaneMutationFromScratch(coreSequence, stateContribution, fundsContribution);
    }

    private void clearLaneUserScratch() {
        for (org.eclipse.collections.impl.list.mutable.primitive.LongArrayList users : laneUserScratch) {
            users.clear();
        }
    }

    private void addLaneUser(long userId) {
        int laneId = topology.accountLaneId(userId);
        laneUserScratch[laneId].add(userId);
    }

    private long stageLaneMutationFromScratch(
            long coreSequence, long stateContribution, long fundsContribution) {
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            if (!laneUserScratch[laneId].isEmpty()) accountLanes[laneId].requireApplySequence(coreSequence);
        }
        long laneMask = 0;
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            org.eclipse.collections.impl.list.mutable.primitive.LongArrayList users = laneUserScratch[laneId];
            if (users.isEmpty()) continue;
            AccountLaneState lane = accountLanes[laneId];
            applyLaneUsers(lane, users, coreSequence, stateContribution, fundsContribution);
            laneMask |= 1L << laneId;
        }
        activePatchBuilder.laneMask(laneMask);
        if (laneMask != 0) requireLaneSequence(coreSequence, laneMask);
        return laneMask;
    }

    public void commitLaneSequence(long coreSequence, long laneMask) {
        assertOwner();
        requireLaneSequence(coreSequence, laneMask);
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            if ((laneMask & 1L << laneId) == 0) continue;
            AccountLaneState lane = accountLanes[laneId];
            lane.committed(coreSequence);
            if (lane.appliedSequence() != coreSequence || lane.committedSequence() != coreSequence) {
                throw new IllegalStateException("account lane read fence differs from lane commit");
            }
        }
    }

    public void rollbackLaneSequence(long coreSequence, long laneMask) {
        assertOwner();
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            if ((laneMask & 1L << laneId) != 0) accountLanes[laneId].rollbackApplied(coreSequence);
        }
    }

    private void requireLaneSequence(long coreSequence, long laneMask) {
        long validMask = accountLanes.length == Long.SIZE ? -1L : (1L << accountLanes.length) - 1L;
        if (coreSequence <= 0 || laneMask == 0 || (laneMask & ~validMask) != 0) {
            throw new IllegalArgumentException("invalid lane commit barrier");
        }
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            if ((laneMask & 1L << laneId) != 0) accountLanes[laneId].requireCommit(coreSequence);
        }
    }

    public RuntimeTreasuryDelta[] applyMatcherSettlement(long coreSequence, long expectedLaneMask,
                                                         MatcherSettlementPlan plan,
                                                         CoreMatchingResult matchingResult,
                                                         RuntimeIdentityRegistry identities) {
        assertOwner();
        long validMask = accountLanes.length == Long.SIZE ? -1L : (1L << accountLanes.length) - 1L;
        if (coreSequence <= 0 || expectedLaneMask == 0 || (expectedLaneMask & ~validMask) != 0
                || matchingResult == null || matchingResult.nativeCommand().coreSequence() != coreSequence
                || plan == null || plan.coreSequence() != coreSequence
                || plan.requiredLaneMask() != expectedLaneMask || identities == null) {
            throw new IllegalArgumentException("invalid matcher settlement lane command");
        }
        long takerOrderId = plan.takerOrderId();
        OrderRuntime taker = order(takerOrderId);
        if (taker == null) throw new IllegalStateException("taker order is missing");
        CoreInstrumentState instrument = instrument(identities.symbol(taker.symbolId()));
        if (instrument == null) throw new IllegalStateException("match instrument is missing");
        int baseAssetId = identities.assetId(instrument.baseAsset());
        int quoteAssetId = identities.assetId(instrument.quoteAsset());
        int settleAssetId = identities.assetId(instrument.settleAsset());
        for (long orderId : plan.orderIds()) {
            captureMatchedOrderBefore(orderId, identities, instrument,
                    baseAssetId, quoteAssetId, settleAssetId);
        }
        Object[] laneResults = executeLaneMutations(
                expectedLaneMask, Math.max(1, plan.tradeEvents().size()), true, currentLaneId -> {
                if (!productLine.isDerivative()) {
                    return RuntimeSpotMatchProcessor.applyLane(takerOrderId, plan.laneEvents(currentLaneId),
                            this, instrument, baseAssetId, quoteAssetId);
                }
                RuntimeTreasuryDelta delta = new RuntimeTreasuryDelta();
                RuntimePerpetualMatchProcessor.applyLane(takerOrderId, plan.laneEvents(currentLaneId),
                        this, identities, instrument, settleAssetId, delta);
                return delta;
            });
        RuntimeTreasuryDelta[] deltas = new RuntimeTreasuryDelta[accountLanes.length];
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            if ((expectedLaneMask & (1L << laneId)) != 0) {
                deltas[laneId] = (RuntimeTreasuryDelta) laneResults[laneId];
            }
        }
        recordMatcherSettlementChanges(plan, identities, instrument,
                baseAssetId, quoteAssetId, settleAssetId);
        return deltas;
    }

    public RuntimeTreasuryDelta[] applyMatcherSettlement(long coreSequence, long expectedLaneMask,
                                                         long takerOrderId, CoreMatchingResult matchingResult,
                                                         RuntimeIdentityRegistry identities) {
        OrderRuntime taker = order(takerOrderId);
        if (taker == null) throw new IllegalStateException("taker order is missing");
        MatcherSettlementPlan plan = MatcherSettlementPlan.build(coreSequence, takerOrderId, taker.userId(),
                new long[]{takerOrderId}, matchingResult, this, identities);
        if (plan.requiredLaneMask() != expectedLaneMask) {
            throw new IllegalStateException("matcher settlement lane mask mismatch");
        }
        return applyMatcherSettlement(coreSequence, expectedLaneMask, plan, matchingResult, identities);
    }

    public RuntimeTreasuryDelta[] applyNoTradeMatcherSettlements(
            long coreSequence, long userId, java.util.List<Long> takerOrderIds,
            java.util.List<CoreMatchingResult> matchingResults, RuntimeIdentityRegistry identities) {
        assertOwner();
        if (coreSequence <= 0 || userId <= 0 || takerOrderIds == null || matchingResults == null
                || takerOrderIds.isEmpty() || takerOrderIds.size() != matchingResults.size()
                || identities == null) {
            throw new IllegalArgumentException("invalid no-trade matcher settlement batch");
        }
        java.util.List<NoTradeSettlement> settlements = new java.util.ArrayList<>(takerOrderIds.size());
        for (int index = 0; index < takerOrderIds.size(); index++) {
            long takerOrderId = takerOrderIds.get(index);
            CoreMatchingResult matchingResult = matchingResults.get(index);
            if (matchingResult == null || matchingResult.nativeCommand().coreSequence() != coreSequence
                    || matchingResult.matcherEvents().stream()
                    .anyMatch(event -> event.eventType() == exchange.core2.core.common.MatcherEventType.TRADE)) {
                throw new IllegalArgumentException("invalid no-trade matcher settlement result");
            }
            OrderRuntime taker = order(takerOrderId);
            if (taker == null || taker.userId() != userId) {
                throw new IllegalStateException("no-trade taker order is missing");
            }
            CoreInstrumentState instrument = instrument(identities.symbol(taker.symbolId()));
            if (instrument == null) throw new IllegalStateException("match instrument is missing");
            int baseAssetId = identities.assetId(instrument.baseAsset());
            int quoteAssetId = identities.assetId(instrument.quoteAsset());
            int settleAssetId = identities.assetId(instrument.settleAsset());
            if (productLine.isDerivative()) {
                RuntimePerpetualMatchProcessor.validateAndPrepare(
                        takerOrderId, matchingResult.matcherEvents(), this, identities);
            } else {
                RuntimeSpotMatchProcessor.validate(takerOrderId, matchingResult.matcherEvents(), this);
            }
            settlements.add(new NoTradeSettlement(takerOrderId, matchingResult, instrument,
                    baseAssetId, quoteAssetId, settleAssetId));
        }
        int laneId = topology.accountLaneId(userId);
        RuntimeTreasuryDelta aggregate = executeUserSettlement(userId, () -> {
            RuntimeTreasuryDelta combined = new RuntimeTreasuryDelta(RuntimeTreasuryDelta.ORDER_BATCH_CAPACITY);
            for (NoTradeSettlement settlement : settlements) {
                RuntimeTreasuryDelta delta;
                if (productLine.isDerivative()) {
                    RuntimePerpetualMatchProcessor.applyLane(settlement.takerOrderId(),
                            settlement.matchingResult().matcherEvents(), this, identities,
                            settlement.instrument(), settlement.settleAssetId(), combined);
                    continue;
                } else {
                    delta = RuntimeSpotMatchProcessor.applyLane(settlement.takerOrderId(),
                            settlement.matchingResult().matcherEvents(), this, settlement.instrument(),
                            settlement.baseAssetId(), settlement.quoteAssetId());
                }
                combined.merge(delta);
            }
            return combined;
        });
        RuntimeTreasuryDelta[] deltas = new RuntimeTreasuryDelta[accountLanes.length];
        deltas[laneId] = aggregate;
        for (NoTradeSettlement settlement : settlements) {
            recordMatcherSettlementChanges(settlement.takerOrderId(), settlement.matchingResult(), identities,
                    settlement.instrument(), settlement.baseAssetId(), settlement.quoteAssetId(),
                    settlement.settleAssetId());
        }
        return deltas;
    }

    public RuntimeTreasuryDelta[] applyPerpetualMatcherSettlements(
            long coreSequence, List<Long> takerOrderIds, List<Long> expectedLaneMasks,
            List<CoreMatchingResult> matchingResults, RuntimeIdentityRegistry identities) {
        assertOwner();
        if (!productLine.isDerivative() || coreSequence <= 0 || takerOrderIds == null
                || expectedLaneMasks == null || matchingResults == null || takerOrderIds.isEmpty()
                || takerOrderIds.size() != expectedLaneMasks.size()
                || takerOrderIds.size() != matchingResults.size() || identities == null) {
            throw new IllegalArgumentException("invalid perpetual matcher settlement batch");
        }
        long validMask = accountLanes.length == Long.SIZE ? -1L : (1L << accountLanes.length) - 1L;
        long selectedLaneMask = 0;
        List<PerpetualMatcherSettlement> settlements = new ArrayList<>(takerOrderIds.size());
        for (int index = 0; index < takerOrderIds.size(); index++) {
            long takerOrderId = takerOrderIds.get(index);
            long expectedLaneMask = expectedLaneMasks.get(index);
            CoreMatchingResult matchingResult = matchingResults.get(index);
            if (takerOrderId <= 0 || expectedLaneMask == 0 || (expectedLaneMask & ~validMask) != 0
                    || matchingResult == null || matchingResult.nativeCommand().coreSequence() != coreSequence) {
                throw new IllegalArgumentException("invalid perpetual matcher settlement item");
            }
            OrderRuntime taker = order(takerOrderId);
            if (taker == null) throw new IllegalStateException("taker order is missing");
            CoreInstrumentState instrument = instrument(identities.symbol(taker.symbolId()));
            if (instrument == null) throw new IllegalStateException("match instrument is missing");
            settlements.add(new PerpetualMatcherSettlement(takerOrderId, expectedLaneMask, matchingResult,
                    instrument, identities.assetId(instrument.baseAsset()),
                    identities.assetId(instrument.quoteAsset()), identities.assetId(instrument.settleAsset())));
            selectedLaneMask |= expectedLaneMask;
        }
        RuntimePerpetualMatchProcessor.validateAndPrepareBatch(
                takerOrderIds, matchingResults, this, identities, perpetualBatchValidationScratch);

        Object[] laneResults = executeLaneMutations(
                selectedLaneMask, Math.max(1, settlements.size()), true, currentLaneId -> {
                RuntimeTreasuryDelta combined = new RuntimeTreasuryDelta(RuntimeTreasuryDelta.ORDER_BATCH_CAPACITY);
                for (PerpetualMatcherSettlement settlement : settlements) {
                    if ((settlement.expectedLaneMask() & (1L << currentLaneId)) == 0) continue;
                    RuntimePerpetualMatchProcessor.applyLane(
                            settlement.takerOrderId(), settlement.matchingResult().matcherEvents(), this, identities,
                            settlement.instrument(), settlement.settleAssetId(), combined);
                }
                return combined;
            });
        RuntimeTreasuryDelta[] deltas = new RuntimeTreasuryDelta[accountLanes.length];
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            if ((selectedLaneMask & (1L << laneId)) != 0) {
                deltas[laneId] = (RuntimeTreasuryDelta) laneResults[laneId];
            }
        }
        for (PerpetualMatcherSettlement settlement : settlements) {
            recordMatcherSettlementChanges(settlement.takerOrderId(), settlement.matchingResult(), identities,
                    settlement.instrument(), settlement.baseAssetId(), settlement.quoteAssetId(),
                    settlement.settleAssetId());
        }
        return deltas;
    }

    private record NoTradeSettlement(
            long takerOrderId,
            CoreMatchingResult matchingResult,
            CoreInstrumentState instrument,
            int baseAssetId,
            int quoteAssetId,
            int settleAssetId) {
    }

    private record PerpetualMatcherSettlement(
            long takerOrderId,
            long expectedLaneMask,
            CoreMatchingResult matchingResult,
            CoreInstrumentState instrument,
            int baseAssetId,
            int quoteAssetId,
            int settleAssetId) {
    }

    private void recordMatcherSettlementChanges(long takerOrderId, CoreMatchingResult matchingResult,
                                                RuntimeIdentityRegistry identities, CoreInstrumentState instrument,
                                                int baseAssetId, int quoteAssetId, int settleAssetId) {
        recordMatchedOrderChanges(takerOrderId, identities, instrument, baseAssetId, quoteAssetId, settleAssetId);
        for (var event : matchingResult.matcherEvents()) {
            if (event.eventType() == exchange.core2.core.common.MatcherEventType.TRADE) {
                recordMatchedOrderChanges(event.matchedOrderId(), identities, instrument,
                        baseAssetId, quoteAssetId, settleAssetId);
            }
        }
    }

    private void captureMatchedOrderBefore(long orderId, RuntimeIdentityRegistry identities,
                                           CoreInstrumentState instrument, int baseAssetId,
                                           int quoteAssetId, int settleAssetId) {
        OrderRuntime order = order(orderId);
        if (order == null) return;
        captureUserBefore(order.userId());
        captureOrderBefore(orderId);
        captureReservationBefore(orderId);
        captureBalanceBefore(order.userId(), baseAssetId);
        captureBalanceBefore(order.userId(), quoteAssetId);
        captureBalanceBefore(order.userId(), settleAssetId);
        if (!productLine.isDerivative()) return;
        Long positionKey = identities.findPositionKey(order.userId(),
                order.positionSide() == CorePositionSide.NET
                        ? instrument.symbol() : instrument.symbol() + ':' + order.positionSide().name());
        if (positionKey != null) capturePositionBefore(positionKey);
    }

    private void recordMatcherSettlementChanges(MatcherSettlementPlan plan,
                                                RuntimeIdentityRegistry identities,
                                                CoreInstrumentState instrument, int baseAssetId,
                                                int quoteAssetId, int settleAssetId) {
        for (long orderId : plan.orderIds()) {
            recordMatchedOrderChanges(orderId, identities, instrument,
                    baseAssetId, quoteAssetId, settleAssetId);
        }
    }

    private void recordMatchedOrderChanges(long orderId, RuntimeIdentityRegistry identities,
                                           CoreInstrumentState instrument, int baseAssetId,
                                           int quoteAssetId, int settleAssetId) {
        OrderRuntime order = order(orderId);
        if (order == null) return;
        changedUsers.add(order.userId());
        changedOrders.add(orderId);
        changedReservations.add(orderId);
        changedBalance(order.userId(), baseAssetId);
        changedBalance(order.userId(), quoteAssetId);
        changedBalance(order.userId(), settleAssetId);
        if (productLine.isDerivative()) {
            Long positionKey = identities.findPositionKey(order.userId(),
                    order.positionSide() == CorePositionSide.NET
                            ? instrument.symbol() : instrument.symbol() + ':' + order.positionSide().name());
            if (positionKey != null) changedPositions.add(positionKey);
        }
    }

    void recordUserSettlementChanges(long userId, int assetId, long positionKey) {
        assertOwner();
        if (userId <= 0 || assetId < 0 || positionKey <= 0) {
            throw new IllegalArgumentException("invalid user settlement changes");
        }
        captureUserBefore(userId);
        captureBalanceBefore(userId, assetId);
        capturePositionBefore(positionKey);
        changedUsers.add(userId);
        changedBalance(userId, assetId);
        changedPositions.add(positionKey);
    }

    public java.util.List<AccountLaneView> accountLaneViews(long laneMask) {
        assertOwner();
        long validMask = accountLanes.length == Long.SIZE ? -1L : (1L << accountLanes.length) - 1L;
        if ((laneMask & ~validMask) != 0) throw new IllegalArgumentException("invalid account lane mask");
        java.util.List<AccountLaneView> selected = new java.util.ArrayList<>(Long.bitCount(laneMask));
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            if ((laneMask & (1L << laneId)) == 0) continue;
            selected.add(accountLaneById(laneId));
        }
        return java.util.List.copyOf(selected);
    }

    void rebuildAccountLaneHashes() {
        assertOwner();
        for (AccountLaneState lane : accountLanes) lane.rebuildLocalHashes();
    }

    public java.util.List<AccountLaneSnapshot> accountLaneSnapshots(
            long fenceSequence, TradingCoreState globalState) {
        assertOwner();
        if (globalState == null || globalState.productLine() != productLine) {
            throw new IllegalArgumentException("global snapshot state is required");
        }
        assertPendingReservationCounts();
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            onLane(laneId, lane -> {
                lane.requireSnapshot(fenceSequence);
                return null;
            });
        }
        java.util.List<AccountLaneSnapshot> snapshots = new java.util.ArrayList<>(accountLanes.length);
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            int currentLaneId = laneId;
            snapshots.add(onLane(currentLaneId, lane -> lane.snapshot(fenceSequence)));
        }
        for (AccountLaneSnapshot snapshot : snapshots) {
            AccountLaneView view = accountLaneById(snapshot.laneId());
            if (view.revision() != snapshot.revision()
                    || view.appliedSequence() != snapshot.appliedSequence()
                    || view.committedSequence() != snapshot.committedSequence()
                    || view.localStateHash() != snapshot.localStateHash()
                    || view.localFundsHash() != snapshot.localFundsHash()) {
                throw new IllegalStateException("account lane changed during snapshot capture");
            }
        }
        return java.util.List.copyOf(snapshots);
    }

    public void requireSnapshotFenceReady() {
        assertOwner();
        assertPendingReservationCounts();
        if (totalPendingReservations != 0 || !pendingReservationsBySequence.isEmpty()
                || !pendingReservationUsers.isEmpty() || snapshotProjectionStateDirty()) {
            throw new IllegalStateException("runtime contains unfinished reservation or patch work");
        }
    }

    private boolean snapshotProjectionStateDirty() {
        return !changedUsers.isEmpty() || !changedBalances.isEmpty() || !changedOrders.isEmpty()
                || !changedReservations.isEmpty() || !changedPositions.isEmpty()
                || !changedLiquidations.isEmpty() || !changedMarkPrices.isEmpty()
                || !changedRiskSnapshots.isEmpty() || !changedRiskScans.isEmpty()
                || !changedClientOrders.isEmpty() || !changedClientOrdersByUser.isEmpty()
                || !changedInstruments.isEmpty() || !changedLeverages.isEmpty()
                || !changedAlgoOrders.isEmpty() || !changedCancelAllAfterTimers.isEmpty()
                || !changedTriggerOrders.isEmpty() || !changedFeePolicies.isEmpty()
                || hasCapturedUsers() || hasCapturedBalances()
                || hasCaptured(patchReservationsBeforeByLane) || hasCaptured(patchOrdersBeforeByLane)
                || !patchLiquidationsBefore.isEmpty() || !patchRiskSnapshotsBefore.isEmpty()
                || !patchLeveragesBefore.isEmpty() || !patchAlgoOrdersBefore.isEmpty()
                || !patchTriggerOrdersBefore.isEmpty() || !patchClientOrdersBefore.isEmpty()
                || !patchTimersBefore.isEmpty() || !patchMarkPricesBefore.isEmpty()
                || !patchRiskScansBefore.isEmpty() || !patchInstrumentsBefore.isEmpty()
                || !patchPendingTransfersBefore.isEmpty() || !patchFeePoliciesBefore.isEmpty()
                || patchNextLiquidationIdChanged || patchRiskScanControlChanged;
    }

    private boolean hasCapturedUsers() {
        for (LongObjectHashMap<UserRuntime> captured : patchUsersBeforeByLane) {
            if (!captured.isEmpty()) return true;
        }
        return false;
    }

    private static boolean hasCaptured(LongObjectHashMap<?>[] capturedByLane) {
        for (LongObjectHashMap<?> captured : capturedByLane) if (!captured.isEmpty()) return true;
        return false;
    }

    private boolean hasCapturedBalances() {
        for (LaneBalancePatches captured : patchBalancesBeforeByLane) {
            if (captured.size() != 0) return true;
        }
        return false;
    }

    public void restoreAccountLaneSnapshots(java.util.List<AccountLaneSnapshot> snapshots, long fenceSequence,
                                            TradingCoreState globalState) {
        assertOwner();
        validateAccountLaneSnapshotManifest(snapshots, fenceSequence, globalState, topology);
        for (AccountLaneSnapshot snapshot : snapshots) {
            int laneId = snapshot.laneId();
            for (Long userId : snapshot.userIds()) {
                if (!onLane(laneId, lane -> lane.users.get(userId) != null)) {
                    throw new IllegalArgumentException("account lane contains an incorrectly routed user");
                }
            }
        }
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            int currentLaneId = laneId;
            onLane(currentLaneId, lane -> {
                lane.users.forEachKey(userId -> {
                    if (!lane.owns(userId)) {
                        throw new IllegalArgumentException("runtime user is absent from its account lane");
                    }
                });
                return null;
            });
        }
        for (AccountLaneSnapshot snapshot : snapshots) {
            onLane(snapshot.laneId(), lane -> {
                lane.restore(snapshot);
                return null;
            });
        }
        pendingReservationsBySequence.clear();
        pendingReservationUsers.clear();
        totalPendingReservations = 0;
        assertPendingReservationCounts();
    }

    public static void validateAccountLaneSnapshotManifest(
            java.util.List<AccountLaneSnapshot> snapshots,
            long fenceSequence,
            TradingCoreState globalState,
            LaneTopology topology) {
        if (snapshots == null || topology == null || snapshots.size() != topology.accountLaneCount()
                || globalState == null || fenceSequence < 0) {
            throw new IllegalArgumentException("incomplete account lane snapshot set");
        }
        @SuppressWarnings("unchecked")
        java.util.TreeSet<Long>[] expectedUsers = new java.util.TreeSet[topology.accountLaneCount()];
        for (int laneId = 0; laneId < expectedUsers.length; laneId++) {
            expectedUsers[laneId] = new java.util.TreeSet<>();
        }
        globalState.users().keySet().forEach(userId ->
                expectedUsers[topology.accountLaneId(userId)].add(userId));
        boolean[] present = new boolean[topology.accountLaneCount()];
        for (AccountLaneSnapshot snapshot : snapshots) {
            int laneId = snapshot.laneId();
            if (laneId < 0 || laneId >= present.length || present[laneId]
                    || snapshot.appliedSequence() != fenceSequence
                    || snapshot.committedSequence() != fenceSequence) {
                throw new IllegalArgumentException("invalid account lane snapshot manifest");
            }
            java.util.TreeSet<Long> actualUsers = new java.util.TreeSet<>(snapshot.userIds());
            if (actualUsers.size() != snapshot.userIds().size()
                    || actualUsers.stream().anyMatch(userId -> topology.accountLaneId(userId) != laneId)) {
                throw new IllegalArgumentException("account lane contains an incorrectly routed user");
            }
            if (!expectedUsers[laneId].equals(actualUsers)) {
                throw new IllegalArgumentException("account lane user manifest differs from global state");
            }
            present[laneId] = true;
        }
    }

    public void bindOwner() {
        Thread current = Thread.currentThread();
        if (owner == null) {
            owner = current;
        } else if (owner != current) {
            throw new IllegalStateException("trading runtime state is bound to another thread");
        }
    }

    public void assertOwner() {
        if (laneCommandScope.get() != null) return;
        bindOwner();
    }

    public void releaseOwnerForHandoff() {
        if (accountLanesStarted) throw new IllegalStateException("account lanes must be closed before handoff");
        for (AccountLaneState lane : accountLanes) lane.releaseOwnerForHandoff();
        treasury.releaseOwnerForHandoff();
        owner = null;
    }

    public ProductLine productLine() {
        assertOwner();
        return productLine;
    }

    public long revision() {
        assertOwner();
        return revision;
    }

    public CommandCheckpoint commandCheckpoint() {
        assertOwner();
        ArrayList<AccountLaneState.Checkpoint> lanes = new ArrayList<>(accountLanes.length);
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            lanes.add(onLane(laneId, AccountLaneState::checkpoint));
        }
        return new CommandCheckpoint(revision, List.copyOf(lanes));
    }

    public void beginOrderBatchMutationScope() {
        assertOwner();
        if (orderBatchMutationScope) throw new IllegalStateException("order batch mutation scope is already active");
        if (snapshotProjectionStateDirty() || !treasury.changedAssets().isEmpty()
                || !treasury.changedFundingSymbols().isEmpty()
                || !treasury.changedLifecycleSymbols().isEmpty()) {
            throw new IllegalStateException("order batch requires a clean command mutation set");
        }
        orderBatchMutationScope = true;
        treasury.beginOrderBatchMutationScope();
    }

    public void endOrderBatchMutationScope() {
        assertOwner();
        if (!orderBatchMutationScope) return;
        treasury.endOrderBatchMutationScope();
        orderBatchMutationScope = false;
    }

    private void rejectUnsupportedOrderBatchMutation(String domain) {
        if (orderBatchMutationScope) {
            throw new IllegalStateException("order batch cannot mutate " + domain);
        }
    }

    public void rollbackActiveCommand(CommandCheckpoint checkpoint, long coreSequence) {
        assertOwner();
        if (checkpoint == null || checkpoint.lanes().size() != accountLanes.length || coreSequence <= 0) {
            throw new IllegalArgumentException("invalid runtime command rollback checkpoint");
        }
        if (pendingReservationsBySequence.containsKey(coreSequence)) {
            completePendingReservations(coreSequence);
        }
        for (RuntimeCommitPatch.ClientOrderKey key : patchClientOrdersBefore.keySet()) {
            PatchBefore<Long> captured = patchClientOrdersBefore.get(key);
            onLane(key.userId(), lane -> {
                removeClientOrderIndex(lane, key.userId(), key.clientKey());
                if (captured.value() != null) {
                    putClientOrderIndex(lane, key.userId(), key.clientKey(), captured.value());
                }
                return null;
            });
        }
        for (LongObjectHashMap<PatchOrderBefore> capturedOrders : patchOrdersBeforeByLane) {
            capturedOrders.forEachKeyValue((orderId, captured) -> rollbackOrder(orderId, captured.value()));
        }
        for (LongObjectHashMap<PatchReservationBefore> capturedReservations : patchReservationsBeforeByLane) {
            capturedReservations.forEachKeyValue((orderId, captured) -> {
                if (captured.pending()) {
                    throw new IllegalStateException("order batch overlapped an existing pending reservation");
                }
                rollbackReservation(orderId, captured.value());
            });
        }
        for (long positionKey : changedPositions.toArray()) rollbackPosition(positionKey);
        for (LaneBalancePatches capturedBalances : patchBalancesBeforeByLane) {
            for (int index = 0; index < capturedBalances.size(); index++) {
                rollbackBalance(capturedBalances.userId(index), capturedBalances.assetId(index),
                        capturedBalances.before(index));
            }
        }
        for (LongObjectHashMap<UserRuntime> capturedUsers : patchUsersBeforeByLane) {
            capturedUsers.forEachKeyValue(this::rollbackUser);
        }
        rollbackCommandGlobals();
        treasury.rollbackChangedValues();
        revision = checkpoint.revision();
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            int id = laneId;
            onLane(id, lane -> { lane.rollback(checkpoint.lanes().get(id)); return null; });
        }
        clearChangedKeys();
    }

    private void rollbackCommandGlobals() {
        patchLiquidationsBefore.forEach((id, before) -> {
            LiquidationRuntime current = liquidation(id);
            if (current != null) onLane(current.userId(), lane -> {
                lane.liquidations.remove(id);
                removeActiveLiquidation(lane, current);
                return null;
            });
            LiquidationRuntime restored = before.value();
            if (restored != null) onLane(restored.userId(), lane -> {
                lane.liquidations.put(id, restored);
                indexActiveLiquidation(lane, restored);
                return null;
            });
        });
        patchRiskSnapshotsBefore.forEach((key, before) -> {
            for (int laneId = 0; laneId < accountLanes.length; laneId++) {
                int id = laneId;
                onLane(id, lane -> { lane.riskSnapshots.remove(key); return null; });
            }
            RiskSnapshotRuntime restored = before.value();
            if (restored != null) onLane(restored.userId(), lane -> {
                lane.riskSnapshots.put(key, restored);
                return null;
            });
        });
        patchLeveragesBefore.forEach((key, before) -> onLane(key.userId(), lane -> {
            if (before.value() == null) {
                lane.leverages.remove(key);
                TreeSet<CoreLeverageKey> keys = lane.leverageKeysByUser.get(key.userId());
                if (keys != null) {
                    keys.remove(key);
                    if (keys.isEmpty()) lane.leverageKeysByUser.remove(key.userId());
                }
            } else {
                lane.leverages.put(key, before.value());
                lane.leverageKeysByUser.getIfAbsentPut(key.userId(), TreeSet::new).add(key);
            }
            return null;
        }));
        patchAlgoOrdersBefore.forEach((id, before) -> {
            for (int laneId = 0; laneId < accountLanes.length; laneId++) {
                int laneIndex = laneId;
                onLane(laneIndex, lane -> { lane.algoOrders.remove(id); return null; });
            }
            CoreAlgoOrderState restored = before.value();
            if (restored != null) onLane(restored.userId(), lane -> {
                lane.algoOrders.put(id, restored);
                return null;
            });
        });
        patchTriggerOrdersBefore.forEach((id, before) -> {
            for (int laneId = 0; laneId < accountLanes.length; laneId++) {
                int laneIndex = laneId;
                onLane(laneIndex, lane -> { lane.triggerOrders.remove(id); return null; });
            }
            CoreTriggerOrderState restored = before.value();
            if (restored != null) onLane(restored.userId(), lane -> {
                lane.triggerOrders.put(id, restored);
                return null;
            });
        });
        patchTimersBefore.forEach((key, before) -> putOrRemove(cancelAllAfterTimers, key, before.value()));
        patchMarkPricesBefore.forEach((id, before) -> putOrRemove(markPrices, id, before.value()));
        patchRiskScansBefore.forEach((id, before) -> putOrRemove(riskScans, id, before.value()));
        patchInstrumentsBefore.forEach((symbol, before) -> putOrRemove(instruments, symbol, before.value()));
        patchPendingTransfersBefore.forEach((id, before) -> putOrRemove(pendingTransfers, id, before.value()));
        patchFeePoliciesBefore.forEach((id, before) -> putOrRemove(feePolicies, id, before.value()));
        if (patchNextLiquidationIdChanged) nextLiquidationId = patchNextLiquidationIdBefore;
        if (patchRiskScanControlChanged) riskScanControl = patchRiskScanControlBefore;
    }

    private static <K, V> void putOrRemove(Map<K, V> values, K key, V value) {
        if (value == null) values.remove(key); else values.put(key, value);
    }

    private static <V> void putOrRemove(IntObjectHashMap<V> values, int key, V value) {
        if (value == null) values.remove(key); else values.put(key, value);
    }

    public record CommandCheckpoint(long revision, List<AccountLaneState.Checkpoint> lanes) {
        public CommandCheckpoint {
            if (revision < 0 || lanes == null) throw new IllegalArgumentException("invalid command checkpoint");
            lanes = List.copyOf(lanes);
        }
    }

    public void setMetadata(ProductLine productLine, long revision) {
        assertOwner();
        if (productLine == null || revision < 0) throw new IllegalArgumentException("invalid runtime metadata");
        this.productLine = productLine;
        this.revision = revision;
    }

    public CoreRiskScanControlView riskScanControl() {
        assertOwner();
        return riskScanControl;
    }

    public void setRiskScanControl(CoreRiskScanControlView riskScanControl) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("risk-scan control");
        if (riskScanControl == null) throw new IllegalArgumentException("risk scan control is required");
        if (!patchRiskScanControlChanged) {
            patchRiskScanControlBefore = this.riskScanControl;
            patchRiskScanControlChanged = true;
        }
        this.riskScanControl = riskScanControl;
    }

    public UserRuntime user(long userId) {
        assertOwner();
        AccountLaneState scoped = laneCommandScope.get();
        return scoped == null ? publishedUsers.get(userId) : scoped.users.get(userId);
    }

    public UserRuntime requireUser(long userId) {
        UserRuntime user = user(userId);
        if (user == null) {
            throw new IllegalArgumentException("runtime user is not registered: " + userId);
        }
        return user;
    }

    public BalanceRuntime balance(long userId, int assetId) {
        assertOwner();
        return onLane(userId, lane -> copyBalance(lane.balances.get(userId), assetId));
    }

    IntObjectHashMap<BalanceRuntime> balancesForUser(long userId) {
        assertOwner();
        return onLane(userId, lane -> copyBalances(lane.balances.get(userId)));
    }

    LongHashSet reservationIdsForUser(long userId) {
        assertOwner();
        return onLane(userId, lane -> {
            LongHashSet orderIds = lane.reservationIdsByUser.get(userId);
            return orderIds == null ? new LongHashSet() : new LongHashSet(orderIds);
        });
    }

    int reservationCountForUser(long userId) {
        assertOwner();
        return onLane(userId, lane -> {
            LongHashSet orderIds = lane.reservationIdsByUser.get(userId);
            return orderIds == null ? 0 : orderIds.size();
        });
    }

    LongHashSet positionKeysForUser(long userId) {
        assertOwner();
        return onLane(userId, lane -> {
            LongHashSet positionKeys = lane.positionKeysByUser.get(userId);
            return positionKeys == null ? new LongHashSet() : new LongHashSet(positionKeys);
        });
    }

    int positionCountForUser(long userId) {
        assertOwner();
        return onLane(userId, lane -> {
            LongHashSet positionKeys = lane.positionKeysByUser.get(userId);
            return positionKeys == null ? 0 : positionKeys.size();
        });
    }

    NavigableSet<CoreLeverageKey> leverageKeysForUser(long userId) {
        assertOwner();
        return onLane(userId, lane -> {
            TreeSet<CoreLeverageKey> keys = lane.leverageKeysByUser.get(userId);
            return keys == null ? Collections.emptyNavigableSet()
                    : Collections.unmodifiableNavigableSet(new TreeSet<>(keys));
        });
    }

    public OrderRuntime order(long orderId) {
        assertOwner();
        AccountLaneState scoped = laneCommandScope.get();
        if (scoped != null) return scoped.orders.get(orderId);
        return publishedOrders.get(orderId);
    }

    public ReservationRuntime reservation(long orderId) {
        assertOwner();
        AccountLaneState scoped = laneCommandScope.get();
        if (scoped != null) return scoped.reservations.get(orderId);
        return publishedReservations.get(orderId);
    }

    private static int indexedLane(LongLongHashMap laneIds, long entityId) {
        long encodedLaneId = laneIds.getIfAbsent(entityId, 0);
        return encodedLaneId == 0 ? -1 : Math.toIntExact(encodedLaneId - 1);
    }

    public PositionRuntime position(long positionKey) {
        assertOwner();
        AccountLaneState scoped = laneCommandScope.get();
        if (scoped != null) return scoped.positions.get(positionKey);
        return publishedPositions.get(positionKey);
    }

    public NavigableSet<Long> positionKeysForUserAndSymbol(long userId, int symbolId) {
        assertOwner();
        return onLane(userId, lane -> {
            LongObjectHashMap<TreeSet<Long>> byUser = lane.positionKeysBySymbolAndUser.get(symbolId);
            TreeSet<Long> keys = byUser == null ? null : byUser.get(userId);
            return keys == null ? Collections.emptyNavigableSet()
                    : Collections.unmodifiableNavigableSet(new TreeSet<>(keys));
        });
    }

    public LiquidationRuntime liquidation(long liquidationId) {
        assertOwner();
        AccountLaneState scoped = laneCommandScope.get();
        if (scoped != null) return scoped.liquidations.get(liquidationId);
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            LiquidationRuntime liquidation = onLane(laneId, lane -> lane.liquidations.get(liquidationId));
            if (liquidation != null) return liquidation;
        }
        return null;
    }

    public LiquidationRuntime activeLiquidation(long userId, int symbolId, CorePositionSide positionSide) {
        assertOwner();
        return onLane(userId, lane -> {
            IntObjectHashMap<LongObjectHashMap<Long>> bySymbol = lane.activeLiquidationIndex.get(userId);
            LongObjectHashMap<Long> bySide = bySymbol == null ? null : bySymbol.get(symbolId);
            Long liquidationId = bySide == null ? null : bySide.get(positionSide.ordinal());
            return liquidationId == null ? null : lane.liquidations.get(liquidationId);
        });
    }

    public boolean hasActiveLiquidationConflict(long userId, int symbolId, long excludedLiquidationId) {
        assertOwner();
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            if (userId != 0 && laneId != topology.accountLaneId(userId)) continue;
            boolean conflict = onLane(laneId, lane -> {
                boolean[] found = new boolean[1];
                lane.liquidations.forEachValue(liquidation -> {
                if (!found[0] && liquidation.liquidationId() != excludedLiquidationId
                        && (liquidation.status() == CoreLiquidationState.Status.PLANNED
                        || liquidation.status() == CoreLiquidationState.Status.ORDERED)
                        && (userId == 0 || liquidation.userId() == userId)
                        && (symbolId < 0 || liquidation.symbolId() == symbolId)) {
                    found[0] = true;
                }
            });
                return found[0];
            });
            if (conflict) return true;
        }
        return false;
    }

    public MarkPriceRuntime markPrice(int symbolId) {
        assertOwner();
        return markPrices.get(symbolId);
    }

    public RiskSnapshotRuntime riskSnapshot(long positionKey) {
        assertOwner();
        AccountLaneState scoped = laneCommandScope.get();
        if (scoped != null) return scoped.riskSnapshots.get(positionKey);
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            RiskSnapshotRuntime snapshot = onLane(laneId, lane -> lane.riskSnapshots.get(positionKey));
            if (snapshot != null) return snapshot;
        }
        return null;
    }

    public RiskScanRuntime riskScan(int symbolId) {
        assertOwner();
        return riskScans.get(symbolId);
    }

    public RiskScanRuntime firstIncompleteRiskScan() {
        assertOwner();
        RiskScanRuntime[] selected = new RiskScanRuntime[1];
        riskScans.forEachKeyValue((symbolId, scan) -> {
            if (!scan.complete()
                    && (selected[0] == null || symbolId < selected[0].symbolId())) {
                selected[0] = scan;
            }
        });
        return selected[0];
    }

    public RiskScanRuntime firstRiskIncompleteScan() {
        assertOwner();
        RiskScanRuntime[] selected = new RiskScanRuntime[1];
        riskScans.forEachValue(scan -> {
            if (!scan.riskComplete() && (selected[0] == null
                    || compareRiskProgress(scan, selected[0]) < 0)) selected[0] = scan;
        });
        return selected[0];
    }

    private static int compareRiskProgress(RiskScanRuntime left, RiskScanRuntime right) {
        int completedUser = Long.compare(left.lastUserId(), right.lastUserId());
        if (completedUser != 0) return completedUser;
        int activeUser = Long.compare(left.riskUserId(), right.riskUserId());
        if (activeUser != 0) return activeUser;
        int phase = Integer.compare(left.riskPhase(), right.riskPhase());
        if (phase != 0) return phase;
        int reservation = Long.compare(left.riskReservationCursor(), right.riskReservationCursor());
        return reservation != 0 ? reservation : Integer.compare(left.symbolId(), right.symbolId());
    }

    public int incompleteRiskScanCount() {
        assertOwner();
        int[] count = new int[1];
        riskScans.forEachValue(scan -> {
            if (!scan.complete()) count[0]++;
        });
        return count[0];
    }

    public long nextLiquidationId() {
        assertOwner();
        return nextLiquidationId;
    }

    public TreasuryRuntime treasury() {
        assertOwner();
        if (laneCommandScope.get() != null) {
            throw new IllegalStateException("Account Lane cannot mutate Sequencer-owned Treasury");
        }
        return treasury;
    }

    public CoreInstrumentState instrument(String symbol) {
        assertOwner();
        return instruments.get(OrderReservation.normalizeSymbol(symbol));
    }

    void putInstrument(CoreInstrumentState instrument) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("instrument state");
        if (instrument == null) {
            throw new IllegalArgumentException("invalid runtime instrument");
        }
        patchInstrumentsBefore.computeIfAbsent(instrument.symbol(),
                symbol -> new PatchBefore<>(instruments.get(symbol)));
        instruments.put(instrument.symbol(), instrument);
        changedInstruments.add(instrument.symbol());
    }

    public Long leverage(CoreLeverageKey key) {
        assertOwner();
        return onLane(key.userId(), lane -> lane.leverages.get(key));
    }

    void putLeverage(CoreLeverageKey key, long leveragePpm) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("leverage state");
        if (key == null || leveragePpm < 1_000_000L) {
            throw new IllegalArgumentException("invalid runtime leverage");
        }
        patchLeveragesBefore.computeIfAbsent(key, value -> new PatchBefore<>(leverage(value)));
        onLane(key.userId(), lane -> {
            lane.leverages.put(key, leveragePpm);
            TreeSet<CoreLeverageKey> userKeys = lane.leverageKeysByUser.get(key.userId());
            if (userKeys == null) {
                userKeys = new TreeSet<>();
                lane.leverageKeysByUser.put(key.userId(), userKeys);
            }
            userKeys.add(key);
            return null;
        });
        changedLeverages.add(key);
    }

    public CoreAlgoOrderState algoOrder(long algoOrderId) {
        assertOwner();
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            CoreAlgoOrderState order = onLane(laneId, lane -> lane.algoOrders.get(algoOrderId));
            if (order != null) return order;
        }
        return null;
    }

    void putAlgoOrder(CoreAlgoOrderState algoOrder) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("algo-order state");
        if (algoOrder == null) throw new IllegalArgumentException("invalid runtime algo order");
        patchAlgoOrdersBefore.computeIfAbsent(algoOrder.algoOrderId(),
                id -> new PatchBefore<>(algoOrder(id)));
        onLane(algoOrder.userId(), lane -> lane.algoOrders.put(algoOrder.algoOrderId(), algoOrder));
        changedAlgoOrders.add(algoOrder.algoOrderId());
    }

    void removeAlgoOrder(long algoOrderId) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("algo-order state");
        patchAlgoOrdersBefore.computeIfAbsent(algoOrderId, id -> new PatchBefore<>(algoOrder(id)));
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            onLane(laneId, lane -> lane.algoOrders.remove(algoOrderId));
        }
        changedAlgoOrders.add(algoOrderId);
    }

    public CoreCancelAllAfterState cancelAllAfterTimer(CoreCancelAllAfterKey key) {
        assertOwner();
        return cancelAllAfterTimers.get(key);
    }

    void putCancelAllAfterTimer(CoreCancelAllAfterKey key, CoreCancelAllAfterState timer) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("timer state");
        if (key == null || timer == null) throw new IllegalArgumentException("invalid runtime cancel-all-after timer");
        patchTimersBefore.computeIfAbsent(key, value -> new PatchBefore<>(cancelAllAfterTimers.get(value)));
        cancelAllAfterTimers.put(key, timer);
        changedCancelAllAfterTimers.add(key);
    }

    public CoreTriggerOrderState triggerOrder(long triggerOrderId) {
        assertOwner();
        AccountLaneState scoped = laneCommandScope.get();
        if (scoped != null) return scoped.triggerOrders.get(triggerOrderId);
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            CoreTriggerOrderState order = onLane(laneId, lane -> lane.triggerOrders.get(triggerOrderId));
            if (order != null) return order;
        }
        return null;
    }

    void putTriggerOrder(CoreTriggerOrderState triggerOrder) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("trigger-order state");
        if (triggerOrder == null) throw new IllegalArgumentException("invalid runtime trigger order");
        patchTriggerOrdersBefore.computeIfAbsent(triggerOrder.triggerOrderId(),
                id -> new PatchBefore<>(triggerOrder(id)));
        onLane(triggerOrder.userId(), lane -> lane.triggerOrders.put(triggerOrder.triggerOrderId(), triggerOrder));
        changedTriggerOrders.add(triggerOrder.triggerOrderId());
    }

    void removeTriggerOrder(long triggerOrderId) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("trigger-order state");
        patchTriggerOrdersBefore.computeIfAbsent(triggerOrderId, id -> new PatchBefore<>(triggerOrder(id)));
        AccountLaneState scoped = laneCommandScope.get();
        if (scoped != null) {
            scoped.triggerOrders.remove(triggerOrderId);
            changedTriggerOrders.add(triggerOrderId);
            return;
        }
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            onLane(laneId, lane -> lane.triggerOrders.remove(triggerOrderId));
        }
        changedTriggerOrders.add(triggerOrderId);
    }

    Map<String, CoreInstrumentState> instrumentsForRuntime() {
        assertOwner();
        return instruments;
    }

    Map<CoreLeverageKey, Long> leveragesForRuntime() {
        assertOwner();
        TreeMap<CoreLeverageKey, Long> values = new TreeMap<>();
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            values.putAll(onLane(laneId, lane -> new TreeMap<>(lane.leverages)));
        }
        return values;
    }

    Map<Long, CoreAlgoOrderState> algoOrdersForRuntime() {
        assertOwner();
        TreeMap<Long, CoreAlgoOrderState> values = new TreeMap<>();
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            values.putAll(onLane(laneId, lane -> new TreeMap<>(lane.algoOrders)));
        }
        return values;
    }

    Map<CoreCancelAllAfterKey, CoreCancelAllAfterState> cancelAllAfterTimersForRuntime() {
        assertOwner();
        return cancelAllAfterTimers;
    }

    Map<Long, CoreTriggerOrderState> triggerOrdersForRuntime() {
        assertOwner();
        AccountLaneState scoped = laneCommandScope.get();
        if (scoped != null) return new TreeMap<>(scoped.triggerOrders);
        TreeMap<Long, CoreTriggerOrderState> values = new TreeMap<>();
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            values.putAll(onLane(laneId, lane -> new TreeMap<>(lane.triggerOrders)));
        }
        return values;
    }

    Map<Long, CoreFeePolicyState> feePoliciesForRuntime() {
        assertOwner();
        return feePolicies;
    }

    public TransferRuntime pendingTransfer(long transferId) {
        assertOwner();
        return pendingTransfers.get(transferId);
    }

    public Map<Long, TransferRuntime> pendingTransfersSnapshot() {
        assertOwner();
        return Collections.unmodifiableMap(new TreeMap<>(pendingTransfers));
    }

    public List<TransferRuntime> pendingTransfers(int limit) {
        assertOwner();
        if (limit <= 0 || limit > com.surprising.aeron.protocol.CorePendingTransferCodec.MAX_RESULTS) {
            throw new IllegalArgumentException("invalid pending transfer limit");
        }
        return pendingTransfers.values().stream().limit(limit).toList();
    }

    public void restorePendingTransfers(Map<Long, TransferRuntime> restored) {
        assertOwner();
        if (restored == null || restored.size() > MAX_PENDING_TRANSFERS
                || restored.entrySet().stream().anyMatch(entry -> entry.getKey() == null
                || entry.getValue() == null || entry.getKey() != entry.getValue().transferId())) {
            throw new IllegalArgumentException("invalid pending transfer snapshot");
        }
        pendingTransfers.clear();
        pendingTransfers.putAll(restored);
    }

    boolean hasPendingTransferCapacity() {
        assertOwner();
        return pendingTransfers.size() < MAX_PENDING_TRANSFERS;
    }

    void putPendingTransfer(TransferRuntime transfer) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("pending-transfer state");
        if (!hasPendingTransferCapacity()) {
            throw new CoreStateRejectedException("PENDING_TRANSFER_CAPACITY_FULL",
                    "pending transfer runtime capacity is full");
        }
        if (transfer == null) throw new IllegalArgumentException("pending transfer is required");
        patchPendingTransfersBefore.computeIfAbsent(transfer.transferId(),
                id -> new PatchBefore<>(pendingTransfers.get(id)));
        if (pendingTransfers.putIfAbsent(transfer.transferId(), transfer) != null) {
            throw new IllegalArgumentException("pending transfer already exists");
        }
    }

    boolean removePendingTransfer(long transferId, long userId) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("pending-transfer state");
        TransferRuntime current = pendingTransfers.get(transferId);
        if (current == null) return false;
        if (current.userId() != userId) {
            throw new CoreStateRejectedException("IDEMPOTENCY_CONFLICT", "transfer belongs to another user");
        }
        patchPendingTransfersBefore.computeIfAbsent(transferId, id -> new PatchBefore<>(current));
        pendingTransfers.remove(transferId);
        return true;
    }

    public Map<Long, CoreFeePolicyState> feePoliciesSnapshot() {
        assertOwner();
        return Collections.unmodifiableMap(new TreeMap<>(feePolicies));
    }

    public void restoreFeePolicies(Map<Long, CoreFeePolicyState> restored) {
        assertOwner();
        if (restored == null) throw new IllegalArgumentException("fee policy snapshot is required");
        feePolicies.clear();
        feePolicies.putAll(restored);
        changedFeePolicies.clear();
    }

    public void upsertFeePolicy(com.surprising.aeron.protocol.UpsertFeePolicyCommand command) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("fee-policy state");
        CoreFeePolicyState next = CoreFeePolicyState.from(command);
        CoreFeePolicyState current = feePolicies.get(next.policyId());
        if (current != null && next.policyRevision() < current.policyRevision()) {
            throw new CoreStateRejectedException("STALE_FEE_POLICY_VERSION", "fee policy version must increase");
        }
        if (current != null && next.policyRevision() == current.policyRevision()) {
            if (!current.equals(next)) {
                throw new CoreStateRejectedException("FEE_POLICY_VERSION_CONFLICT",
                        "fee policy version contains different data");
            }
            return;
        }
        patchFeePoliciesBefore.computeIfAbsent(next.policyId(), id -> new PatchBefore<>(current));
        feePolicies.put(next.policyId(), next);
        changedFeePolicies.add(next.policyId());
        setMetadata(productLine, Math.incrementExact(revision));
    }

    public CoreFeeRate resolveFee(long userId, String symbol, long clusterTimestamp,
                                  CoreInstrumentState instrument) {
        assertOwner();
        String normalizedSymbol = OrderReservation.normalizeSymbol(symbol);
        CoreFeePolicyState selected = feePolicies.values().stream()
                .filter(policy -> policy.effective(userId, normalizedSymbol, clusterTimestamp))
                .min(CoreFeePolicyState::compareTo)
                .orElse(null);
        return selected == null
                ? new CoreFeeRate(instrument.makerFeeRatePpm(), instrument.takerFeeRatePpm(), 0)
                : new CoreFeeRate(selected.makerFeeRatePpm(), selected.takerFeeRatePpm(),
                selected.policyRevision());
    }

    public void replaceAuxiliaryState(TradingCoreState source) {
        assertOwner();
        setMetadata(source.productLine(), source.revision());
        setRiskScanControl(source.riskState().scanControl());
        instruments.clear();
        instruments.putAll(source.instruments());
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            onLane(laneId, lane -> {
                lane.leverages.clear();
                lane.leverageKeysByUser.clear();
                lane.algoOrders.clear();
                lane.triggerOrders.clear();
                return null;
            });
        }
        source.leverages().forEach(this::putLeverage);
        source.algoOrders().values().forEach(this::putAlgoOrder);
        cancelAllAfterTimers.clear();
        cancelAllAfterTimers.putAll(source.cancelAllAfterTimers());
        source.triggerOrders().values().forEach(this::putTriggerOrder);
    }

    public Long orderIdByClient(long userId, long clientKey) {
        assertOwner();
        return onLane(userId, lane -> {
            LongObjectHashMap<Long> userClientOrders = lane.clientOrderIndex.get(userId);
            return userClientOrders == null ? null : userClientOrders.get(clientKey);
        });
    }

    public void putUser(UserRuntime user) {
        assertOwner();
        captureUserBefore(user.userId());
        onLane(user.userId(), lane -> {
            lane.users.put(user.userId(), user);
            lane.registerUser(user.userId());
            return null;
        });
        publishUser(user.userId(), user);
        changedUsers.add(user.userId());
    }

    public void advanceUserRevision(long userId) {
        assertOwner();
        captureUserBefore(userId);
        UserRuntime current = requireUser(userId);
        UserRuntime advanced = new UserRuntime(current.productLine(), userId,
                Math.incrementExact(current.revision()), current.positionMode());
        onLane(userId, lane -> lane.users.put(userId, advanced));
        publishUser(userId, advanced);
        if (laneCommandScope.get() == null) changedUsers.add(userId);
    }

    public void removeUser(long userId) {
        assertOwner();
        captureUserBefore(userId);
        LongHashSet clientKeys = onLane(userId, lane -> {
            LongHashSet keys = new LongHashSet();
            LongObjectHashMap<Long> userClientOrders = lane.clientOrderIndex.get(userId);
            if (userClientOrders != null) userClientOrders.forEachKeyValue((clientKey, orderId) -> {
                keys.add(clientKey);
                removeClientOrderReverse(lane, orderId, clientKey);
            });
            lane.users.remove(userId);
            lane.removeUser(userId);
            lane.balances.remove(userId);
            lane.clientOrderIndex.remove(userId);
            lane.reservationIdsByUser.remove(userId);
            lane.positionKeysByUser.remove(userId);
            lane.leverageKeysByUser.remove(userId);
            return keys;
        });
        publishUser(userId, null);
        clientKeys.forEach(clientKey -> {
            changedClientOrders.add(clientKey);
            changedClientOrder(userId, clientKey);
        });
        changedUsers.add(userId);
    }

    public void putBalance(BalanceRuntime balance) {
        assertOwner();
        long userId = balance.userId();
        int assetId = balance.assetId();
        long availableUnits = balance.availableUnits();
        long lockedUnits = balance.lockedUnits();
        captureUserBefore(userId);
        captureBalanceBefore(userId, assetId);
        onLane(userId, lane -> {
            IntObjectHashMap<BalanceRuntime> userBalances = lane.balances.get(userId);
            if (userBalances == null) {
                userBalances = new IntObjectHashMap<>();
                lane.balances.put(userId, userBalances);
            }
            userBalances.put(assetId, new BalanceRuntime(userId, assetId, availableUnits, lockedUnits));
            return null;
        });
        if (laneCommandScope.get() == null) {
            changedBalance(userId, assetId);
            changedUsers.add(userId);
        }
    }

    public void putOrder(OrderRuntime order) {
        assertOwner();
        captureUserBefore(order.userId());
        captureOrderBefore(order.orderId());
        onLane(order.userId(), lane -> lane.orders.put(order.orderId(), order));
        publishOrder(order.orderId(), order);
        orderLaneIds.put(order.orderId(), topology.accountLaneId(order.userId()) + 1L);
        changedOrders.add(order.orderId());
        changedUsers.add(order.userId());
    }

    public void putReservation(ReservationRuntime reservation) {
        assertOwner();
        captureUserBefore(reservation.userId());
        captureReservationBefore(reservation.orderId());
        ReservationRuntime previous = reservation(reservation.orderId());
        if (previous != null && onLane(previous.userId(), lane -> lane.pendingReservation(previous.orderId()))) {
            throw new IllegalStateException("pending reservation must be replaced through its owner lane");
        }
        if (previous != null) {
            onLane(previous.userId(), lane -> {
                lane.reservations.remove(previous.orderId());
                removeUserEntity(lane.reservationIdsByUser, previous.userId(), previous.orderId());
                return null;
            });
        }
        onLane(reservation.userId(), lane -> {
            lane.reservations.put(reservation.orderId(), reservation);
            addUserEntity(lane.reservationIdsByUser, reservation.userId(), reservation.orderId());
            return null;
        });
        publishReservation(reservation.orderId(), reservation);
        if (previous == null) {
            reservationLaneIds.put(reservation.orderId(), topology.accountLaneId(reservation.userId()) + 1L);
        }
        changedReservations.add(reservation.orderId());
        changedUsers.add(reservation.userId());
        if (previous != null) changedUsers.add(previous.userId());
    }

    public void replaceOrder(OrderRuntime order) {
        assertOwner();
        captureUserBefore(order.userId());
        captureOrderBefore(order.orderId());
        OrderRuntime previous = order(order.orderId());
        if (previous != null && previous.userId() != order.userId()) {
            throw new IllegalArgumentException("runtime order owner cannot change");
        }
        onLane(order.userId(), lane -> lane.orders.put(order.orderId(), order));
        publishOrder(order.orderId(), order);
        if (previous == null) {
            orderLaneIds.put(order.orderId(), topology.accountLaneId(order.userId()) + 1L);
        }
        if (laneCommandScope.get() == null) {
            changedOrders.add(order.orderId());
            changedUsers.add(order.userId());
        }
    }

    public void removeOrder(long orderId) {
        assertOwner();
        captureOrderBefore(orderId);
        OrderRuntime previous = order(orderId);
        if (previous != null) {
            captureUserBefore(previous.userId());
            onLane(previous.userId(), lane -> lane.orders.remove(orderId));
            publishOrder(orderId, null);
            orderLaneIds.removeKey(orderId);
            changedOrders.add(orderId);
            changedUsers.add(previous.userId());
        }
    }

    public void replaceReservation(ReservationRuntime reservation) {
        assertOwner();
        captureUserBefore(reservation.userId());
        captureReservationBefore(reservation.orderId());
        ReservationRuntime previous = reservation(reservation.orderId());
        if (previous != null && previous.userId() != reservation.userId()) {
            throw new IllegalArgumentException("runtime reservation owner cannot change");
        }
        if (previous != null) {
            onLane(previous.userId(), lane -> {
                lane.replacePendingReservation(previous, reservation);
                lane.reservations.remove(previous.orderId());
                removeUserEntity(lane.reservationIdsByUser, previous.userId(), previous.orderId());
                return null;
            });
        }
        onLane(reservation.userId(), lane -> {
            lane.reservations.put(reservation.orderId(), reservation);
            addUserEntity(lane.reservationIdsByUser, reservation.userId(), reservation.orderId());
            return null;
        });
        publishReservation(reservation.orderId(), reservation);
        if (previous == null) {
            reservationLaneIds.put(reservation.orderId(), topology.accountLaneId(reservation.userId()) + 1L);
        }
        if (laneCommandScope.get() == null) {
            changedReservations.add(reservation.orderId());
            changedUsers.add(reservation.userId());
            if (previous != null) changedUsers.add(previous.userId());
        }
    }

    public void removeReservation(long orderId, long userId) {
        assertOwner();
        captureUserBefore(userId);
        captureReservationBefore(orderId);
        onLane(userId, lane -> {
            ReservationRuntime current = lane.reservations.get(orderId);
            if (current == null || current.userId() != userId) {
                throw new IllegalArgumentException("runtime reservation is not registered: " + orderId);
            }
            if (lane.pendingReservation(orderId)) {
                throw new IllegalStateException("pending reservation must complete before removal");
            }
            lane.reservations.remove(orderId);
            removeUserEntity(lane.reservationIdsByUser, userId, orderId);
            return null;
        });
        publishReservation(orderId, null);
        reservationLaneIds.removeKey(orderId);
        changedReservations.add(orderId);
        changedUsers.add(userId);
    }

    public void replaceBalance(BalanceRuntime balance) {
        assertOwner();
        long userId = balance.userId();
        int assetId = balance.assetId();
        long availableUnits = balance.availableUnits();
        long lockedUnits = balance.lockedUnits();
        captureUserBefore(userId);
        captureBalanceBefore(userId, assetId);
        onLane(userId, lane -> {
            IntObjectHashMap<BalanceRuntime> balances = lane.balances.get(userId);
            BalanceRuntime current = balances == null ? null : balances.get(assetId);
            if (current == null) throw new IllegalArgumentException("runtime balance is not registered");
            current.replace(availableUnits, lockedUnits);
            return null;
        });
        if (laneCommandScope.get() == null) {
            changedBalance(userId, assetId);
            changedUsers.add(userId);
        }
    }

    public void removeBalance(long userId, int assetId) {
        assertOwner();
        captureUserBefore(userId);
        captureBalanceBefore(userId, assetId);
        onLane(userId, lane -> {
            IntObjectHashMap<BalanceRuntime> userBalances = lane.balances.get(userId);
            if (userBalances == null || userBalances.remove(assetId) == null) {
                throw new IllegalArgumentException("runtime balance is not registered: " + userId + '/' + assetId);
            }
            return null;
        });
        changedBalance(userId, assetId);
        changedUsers.add(userId);
    }

    public void replacePosition(long positionKey, PositionRuntime position) {
        assertOwner();
        captureUserBefore(position.userId());
        capturePositionBefore(positionKey, position.userId());
        PositionRuntime previous = position(positionKey);
        if (previous != null && previous.userId() != position.userId()) {
            throw new IllegalArgumentException("runtime position owner cannot change");
        }
        onLane(position.userId(), lane -> {
            if (previous != null) unindexPosition(lane, positionKey, previous);
            lane.positions.put(positionKey, position);
            indexPosition(lane, positionKey, position);
            return null;
        });
        publishPosition(positionKey, position);
        if (laneCommandScope.get() == null) {
            positionLaneIds.put(positionKey, topology.accountLaneId(position.userId()) + 1L);
            changedPositions.add(positionKey);
            changedUsers.add(position.userId());
            if (previous != null) changedUsers.add(previous.userId());
        }
    }

    public void putLiquidation(LiquidationRuntime liquidation) {
        assertOwner();
        captureUserBefore(liquidation.userId());
        captureLiquidationBefore(liquidation.liquidationId());
        if (liquidation(liquidation.liquidationId()) != null) {
            throw new IllegalArgumentException("runtime liquidation already exists: " + liquidation.liquidationId());
        }
        onLane(liquidation.userId(), lane -> {
            lane.liquidations.put(liquidation.liquidationId(), liquidation);
            indexActiveLiquidation(lane, liquidation);
            return null;
        });
        changedLiquidations.add(liquidation.liquidationId());
        changedUsers.add(liquidation.userId());
    }

    public void putMarkPrice(MarkPriceRuntime markPrice) {
        assertOwner();
        captureMarkPriceBefore(markPrice.symbolId());
        markPrices.put(markPrice.symbolId(), markPrice);
        changedMarkPrices.add(markPrice.symbolId());
    }

    public void putRiskSnapshot(long positionKey, RiskSnapshotRuntime snapshot) {
        assertOwner();
        captureUserBefore(snapshot.userId());
        captureRiskSnapshotBefore(positionKey);
        onLane(snapshot.userId(), lane -> lane.riskSnapshots.put(positionKey, snapshot));
        changedRiskSnapshots.add(positionKey);
        changedUsers.add(snapshot.userId());
    }

    public void putRiskScan(RiskScanRuntime scan) {
        assertOwner();
        captureRiskScanBefore(scan.symbolId());
        riskScans.put(scan.symbolId(), scan);
        changedRiskScans.add(scan.symbolId());
    }

    public void setNextLiquidationId(long nextLiquidationId) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("liquidation sequence");
        if (nextLiquidationId <= 0) throw new IllegalArgumentException("invalid next liquidation id");
        if (!patchNextLiquidationIdChanged) {
            patchNextLiquidationIdBefore = this.nextLiquidationId;
            patchNextLiquidationIdChanged = true;
        }
        this.nextLiquidationId = nextLiquidationId;
    }

    public void replaceLiquidation(LiquidationRuntime liquidation) {
        assertOwner();
        captureUserBefore(liquidation.userId());
        captureLiquidationBefore(liquidation.liquidationId());
        LiquidationRuntime previous = liquidation(liquidation.liquidationId());
        if (previous == null) {
            throw new IllegalArgumentException("runtime liquidation is not registered: "
                    + liquidation.liquidationId());
        }
        if (previous.userId() != liquidation.userId()) {
            onLane(previous.userId(), lane -> {
                removeActiveLiquidation(lane, previous);
                lane.liquidations.remove(previous.liquidationId());
                return null;
            });
        }
        onLane(liquidation.userId(), lane -> {
            if (previous.userId() == liquidation.userId()) removeActiveLiquidation(lane, previous);
            lane.liquidations.put(liquidation.liquidationId(), liquidation);
            indexActiveLiquidation(lane, liquidation);
            return null;
        });
        changedLiquidations.add(liquidation.liquidationId());
        changedUsers.add(liquidation.userId());
        changedUsers.add(previous.userId());
    }

    public void removePosition(long positionKey, long userId) {
        assertOwner();
        captureUserBefore(userId);
        capturePositionBefore(positionKey);
        onLane(userId, lane -> {
            PositionRuntime current = lane.positions.get(positionKey);
            if (current == null || current.userId() != userId) {
                throw new IllegalArgumentException("runtime position is not registered: " + positionKey);
            }
            lane.positions.remove(positionKey);
            unindexPosition(lane, positionKey, current);
            return null;
        });
        publishPosition(positionKey, null);
        positionLaneIds.removeKey(positionKey);
        changedPositions.add(positionKey);
        changedUsers.add(userId);
    }

    public void removeLiquidation(long liquidationId) {
        assertOwner();
        captureLiquidationBefore(liquidationId);
        LiquidationRuntime previous = liquidation(liquidationId);
        if (previous != null) {
            captureUserBefore(previous.userId());
            onLane(previous.userId(), lane -> {
                lane.liquidations.remove(liquidationId);
                removeActiveLiquidation(lane, previous);
                return null;
            });
            changedLiquidations.add(liquidationId);
            changedUsers.add(previous.userId());
        }
    }

    public void removeMarkPrice(int symbolId) {
        assertOwner();
        captureMarkPriceBefore(symbolId);
        markPrices.remove(symbolId);
        changedMarkPrices.add(symbolId);
    }

    public void removeRiskSnapshot(long positionKey) {
        assertOwner();
        captureRiskSnapshotBefore(positionKey);
        RiskSnapshotRuntime previous = riskSnapshot(positionKey);
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            onLane(laneId, lane -> lane.riskSnapshots.remove(positionKey));
        }
        changedRiskSnapshots.add(positionKey);
        if (previous != null) changedUsers.add(previous.userId());
    }

    public void removeRiskScan(int symbolId) {
        assertOwner();
        captureRiskScanBefore(symbolId);
        riskScans.remove(symbolId);
        changedRiskScans.add(symbolId);
    }

    public void cancelOrder(long orderId, long userId, long releaseUnits) {
        assertOwner();
        captureUserBefore(userId);
        captureOrderBefore(orderId);
        captureReservationBefore(orderId);
        CanceledOrder canceled = onLane(userId, lane -> {
            OrderRuntime order = lane.orders.get(orderId);
            ReservationRuntime reservation = lane.reservations.get(orderId);
            if (order == null || reservation == null || order.userId() != userId || order.canceled()) {
                throw new IllegalArgumentException("runtime order is not cancelable: " + orderId);
            }
            if (releaseUnits != reservation.reservedUnits()) {
                throw new IllegalArgumentException("runtime cancellation release mismatch: " + orderId);
            }
            IntObjectHashMap<BalanceRuntime> balances = lane.balances.get(userId);
            BalanceRuntime balance = balances == null ? null : balances.get(reservation.assetId());
            if (balance == null) {
                throw new IllegalArgumentException("runtime cancellation balance is missing: " + orderId);
            }
            captureBalanceBefore(userId, reservation.assetId());
            balance.release(releaseUnits);
            OrderRuntime terminalOrder = order.withStatus(CoreOrderStatus.CANCELED,
                    Math.incrementExact(order.revision()));
            ReservationRuntime released = reservation.release(releaseUnits);
            lane.replacePendingReservation(reservation, released);
            lane.orders.put(orderId, terminalOrder);
            lane.reservations.put(orderId, released);
            return new CanceledOrder(terminalOrder, released);
        });
        publishOrder(orderId, canceled.order());
        publishReservation(orderId, canceled.reservation());
        changedOrders.add(orderId);
        changedReservations.add(orderId);
        changedUsers.add(userId);
        changedBalance(userId, canceled.reservation().assetId());
        advanceUserRevision(userId);
    }

    public void releaseTerminalReservation(long orderId) {
        assertOwner();
        captureReservationBefore(orderId);
        OrderRuntime order = order(orderId);
        if (order == null) throw new IllegalArgumentException("runtime order is not terminal: " + orderId);
        captureUserBefore(order.userId());
        TerminalRelease release = onLane(order.userId(), lane -> {
            ReservationRuntime reservation = lane.reservations.get(orderId);
            if (reservation == null || !order.canceled()) {
                throw new IllegalArgumentException("runtime order is not terminal: " + orderId);
            }
            long releaseUnits = reservation.reservedUnits();
            if (releaseUnits == 0) return new TerminalRelease(0, reservation.assetId());
            IntObjectHashMap<BalanceRuntime> balances = lane.balances.get(order.userId());
            BalanceRuntime balance = balances == null ? null : balances.get(reservation.assetId());
            if (balance == null) throw new IllegalStateException("runtime terminal balance is missing: " + orderId);
            captureBalanceBefore(order.userId(), reservation.assetId());
            balance.release(releaseUnits);
            ReservationRuntime released = reservation.release(releaseUnits);
            lane.replacePendingReservation(reservation, released);
            lane.reservations.put(orderId, released);
            publishReservation(orderId, released);
            return new TerminalRelease(releaseUnits, reservation.assetId());
        });
        if (release.units() == 0) return;
        if (laneCommandScope.get() == null) {
            changedReservations.add(orderId);
            changedUsers.add(order.userId());
            changedBalance(order.userId(), release.assetId());
        }
    }

    public void putClientOrder(long userId, long clientKey, long orderId) {
        assertOwner();
        captureUserBefore(userId);
        captureClientOrderBefore(userId, clientKey);
        onLane(userId, lane -> {
            putClientOrderIndex(lane, userId, clientKey, orderId);
            return null;
        });
        changedClientOrders.add(clientKey);
        changedClientOrder(userId, clientKey);
        changedUsers.add(userId);
    }

    public void removeClientOrder(long userId, long clientKey) {
        assertOwner();
        captureUserBefore(userId);
        captureClientOrderBefore(userId, clientKey);
        onLane(userId, lane -> {
            removeClientOrderIndex(lane, userId, clientKey);
            return null;
        });
        changedClientOrders.add(clientKey);
        changedClientOrder(userId, clientKey);
        changedUsers.add(userId);
    }

    public void pruneTerminalOrders(RuntimeIdentityRegistry identities, List<Long> orderIds) {
        assertOwner();
        if (identities == null || orderIds == null) {
            throw new IllegalArgumentException("terminal order prune input is required");
        }
        List<TerminalOrderPrune> prunes = new ArrayList<>(orderIds.size());
        for (Long orderId : orderIds) {
            if (orderId == null) throw new IllegalArgumentException("terminal order id is required");
            OrderRuntime order = order(orderId);
            if (order == null || !order.status().terminal()) {
                throw new IllegalStateException("order is not terminal: " + orderId);
            }
            ReservationRuntime reservation = reservation(orderId);
            if (reservation != null && reservation.reservedUnits() != 0) {
                throw new IllegalStateException("terminal order retains funded reservation: " + orderId);
            }
            captureOrderBefore(orderId);
            captureReservationBefore(orderId);
            prunes.add(new TerminalOrderPrune(orderId, order.userId(),
                    identities.clientKey(order.userId(), order.clientOrderId())));
            long clientKey = identities.clientKey(order.userId(), order.clientOrderId());
            if (clientKey != 0) captureClientOrderBefore(order.userId(), clientKey);
        }
        Object[] results = executeOwnerSettlements(prunes, TerminalOrderPrune::userId, laneId -> {
            AccountLaneState lane = laneCommandScope.get();
            List<TerminalOrderPruned> pruned = new ArrayList<>();
            for (TerminalOrderPrune prune : prunes) {
                if (topology.accountLaneId(prune.userId()) != laneId) continue;
                OrderRuntime order = lane.orders.get(prune.orderId());
                if (order == null || !order.status().terminal() || order.userId() != prune.userId()) {
                    throw new IllegalStateException("terminal order changed during prune: " + prune.orderId());
                }
                ReservationRuntime reservation = lane.reservations.get(prune.orderId());
                int reservationAssetId = -1;
                if (reservation != null) {
                    if (lane.pendingReservation(prune.orderId())) {
                        throw new IllegalStateException(
                                "pending reservation must complete before terminal pruning: " + prune.orderId());
                    }
                    if (reservation.reservedUnits() != 0) {
                        throw new IllegalStateException(
                                "terminal order retains funded reservation: " + prune.orderId());
                    }
                    reservationAssetId = reservation.assetId();
                    lane.reservations.remove(prune.orderId());
                    removeUserEntity(lane.reservationIdsByUser, prune.userId(), prune.orderId());
                }
                lane.orders.remove(prune.orderId());
                boolean clientRemoved = false;
                if (prune.clientKey() != 0) {
                    LongObjectHashMap<Long> clients = lane.clientOrderIndex.get(prune.userId());
                    if (clients != null && Long.valueOf(prune.orderId()).equals(clients.get(prune.clientKey()))) {
                        removeClientOrderIndex(lane, prune.userId(), prune.clientKey());
                        clientRemoved = true;
                    }
                }
                pruned.add(new TerminalOrderPruned(prune.orderId(), prune.userId(), reservationAssetId,
                        reservation != null, prune.clientKey(), clientRemoved));
            }
            return List.copyOf(pruned);
        });
        for (Object result : results) {
            if (result == null) continue;
            @SuppressWarnings("unchecked")
            List<TerminalOrderPruned> pruned = (List<TerminalOrderPruned>) result;
            for (TerminalOrderPruned terminal : pruned) {
                publishOrder(terminal.orderId(), null);
                orderLaneIds.removeKey(terminal.orderId());
                changedOrders.add(terminal.orderId());
                changedUsers.add(terminal.userId());
                if (terminal.reservationRemoved()) {
                    publishReservation(terminal.orderId(), null);
                    reservationLaneIds.removeKey(terminal.orderId());
                    changedReservations.add(terminal.orderId());
                    changedBalance(terminal.userId(), terminal.reservationAssetId());
                }
                if (terminal.clientRemoved()) {
                    changedClientOrders.add(terminal.clientKey());
                    changedClientOrder(terminal.userId(), terminal.clientKey());
                }
            }
        }
    }

    public LongHashSet changedUsers() {
        assertOwner();
        return new LongHashSet(changedUsers);
    }

    public IntHashSet changedBalances(long userId) {
        assertOwner();
        IntHashSet assets = changedBalances.get(userId);
        return assets == null ? new IntHashSet() : new IntHashSet(assets);
    }

    public boolean hasChangedBalance(long userId, int assetId) {
        assertOwner();
        IntHashSet assets = changedBalances.get(userId);
        return assets != null && assets.contains(assetId);
    }

    void markBalanceChanged(long userId, int assetId) {
        assertOwner();
        changedBalance(userId, assetId);
        changedUsers.add(userId);
    }

    public LongHashSet changedOrders() {
        assertOwner();
        return new LongHashSet(changedOrders);
    }

    public LongHashSet changedReservations() {
        assertOwner();
        return new LongHashSet(changedReservations);
    }

    public LongHashSet changedPositions() {
        assertOwner();
        return new LongHashSet(changedPositions);
    }

    public boolean hasChangedPositions() {
        assertOwner();
        return !changedPositions.isEmpty();
    }

    LongHashSet changedLiquidations() {
        assertOwner();
        return new LongHashSet(changedLiquidations);
    }

    IntHashSet changedMarkPrices() {
        assertOwner();
        return new IntHashSet(changedMarkPrices);
    }

    LongHashSet changedRiskSnapshots() {
        assertOwner();
        return new LongHashSet(changedRiskSnapshots);
    }

    IntHashSet changedRiskScans() {
        assertOwner();
        return new IntHashSet(changedRiskScans);
    }

    public LongHashSet changedClientOrders() {
        assertOwner();
        return new LongHashSet(changedClientOrders);
    }

    LongObjectHashMap<LongHashSet> changedClientOrdersByUser() {
        assertOwner();
        LongObjectHashMap<LongHashSet> result = new LongObjectHashMap<>();
        changedClientOrdersByUser.forEachKeyValue((userId, keys) -> result.put(userId, new LongHashSet(keys)));
        return result;
    }

    TreeSet<String> changedInstruments() {
        assertOwner();
        return new TreeSet<>(changedInstruments);
    }

    TreeSet<CoreLeverageKey> changedLeverages() {
        assertOwner();
        return new TreeSet<>(changedLeverages);
    }

    LongHashSet changedAlgoOrders() {
        assertOwner();
        return new LongHashSet(changedAlgoOrders);
    }

    TreeSet<CoreCancelAllAfterKey> changedCancelAllAfterTimers() {
        assertOwner();
        return new TreeSet<>(changedCancelAllAfterTimers);
    }

    LongHashSet changedTriggerOrders() {
        assertOwner();
        return new LongHashSet(changedTriggerOrders);
    }

    LongHashSet changedFeePolicies() {
        assertOwner();
        return new LongHashSet(changedFeePolicies);
    }

    public PreparedCommit prepareCommitPatch(long sequence,
                                                  RuntimeIdentityRegistry identities,
                                                  long previousRevision,
                                                  CoreMatcherTransition matcherTransition,
                                                  long committedLaneMask,
                                                  long beforeBusinessStateHash,
                                                  long businessStateHash,
                                                  long beforeFundsStateHash,
                                                  long fundsStateHash,
                                                  boolean externalAdjustment) {
        assertOwner();
        if (sequence <= 0 || identities == null || previousRevision < 0
                || matcherTransition == null) {
            throw new IllegalArgumentException("invalid runtime commit patch capture");
        }
        long validLaneMask = accountLanes.length == Long.SIZE ? -1L : (1L << accountLanes.length) - 1L;
        if (committedLaneMask < 0 || (committedLaneMask & ~validLaneMask) != 0) {
            throw new IllegalArgumentException("invalid runtime commit lane mask");
        }
        RuntimeCommitPatch.Builder builder = activePatchBuilder
                .sequences(Math.subtractExact(sequence, 1), sequence)
                .matcherTransition(matcherTransition);
        for (long userId : changedUsers.toArray()) {
            LongObjectHashMap<UserRuntime> capturedUsers =
                    patchUsersBeforeByLane[topology.accountLaneId(userId)];
            UserRuntime captured = capturedUsers.get(userId);
            UserRuntime current = user(userId);
            recordUserChange(builder, userId, captured == null && !capturedUsers.containsKey(userId)
                    ? current : captured, current);
        }
        for (int laneId = 0; laneId < patchBalancesBeforeByLane.length; laneId++) {
            LaneBalancePatches capturedBalances = patchBalancesBeforeByLane[laneId];
            for (int index = 0; index < capturedBalances.size(); index++) {
                long userId = capturedBalances.userId(index);
                int assetId = capturedBalances.assetId(index);
                RuntimeCommitPatch.UserBalance before = capturedBalances.before(index);
                BalanceRuntime balance = balance(userId, assetId);
                RuntimeCommitPatch.UserBalance after = balance == null ? null : new RuntimeCommitPatch.UserBalance(
                        balance.availableUnits(), balance.lockedUnits(), pendingReservedUnits(userId, assetId));
                if (!java.util.Objects.equals(before, after)) {
                    builder.recordBalance(laneId, userId, assetId, before, after);
                }
            }
        }
        for (LongObjectHashMap<PatchReservationBefore> capturedReservations : patchReservationsBeforeByLane) {
            capturedReservations.forEachKeyValue((orderId, before) -> {
                ReservationRuntime after = reservation(orderId);
                ReservationRuntime beforeValue = before.value();
                long userId = after != null ? after.userId() : beforeValue == null ? 0 : beforeValue.userId();
                boolean pendingAfter = after != null && pendingReservation(orderId, after.userId());
                if (userId != 0
                        && (!java.util.Objects.equals(beforeValue, after) || before.pending() != pendingAfter)) {
                    builder.recordReservation(topology.accountLaneId(userId), orderId,
                            beforeValue, after, before.pending(), pendingAfter);
                }
            });
        }
        for (LongObjectHashMap<PatchOrderBefore> capturedOrders : patchOrdersBeforeByLane) {
            capturedOrders.forEachKeyValue((orderId, before) -> {
                OrderRuntime after = order(orderId);
                OrderRuntime beforeValue = before.value();
                long userId = after != null ? after.userId() : beforeValue == null ? 0 : beforeValue.userId();
                boolean pendingAfter = after != null && pendingReservation(orderId, after.userId());
                OrderRuntime visibleBefore = before.pending() ? null : beforeValue;
                OrderRuntime visibleAfter = pendingAfter ? null : after;
                if (userId != 0 && !java.util.Objects.equals(visibleBefore, visibleAfter)) {
                    CoreOrderState businessBefore = visibleBefore == null ? null
                            : RuntimeStateMaterializer.orderSnapshot(visibleBefore, identities);
                    CoreOrderState businessAfter = visibleAfter == null ? null
                            : RuntimeStateMaterializer.orderSnapshot(visibleAfter, identities);
                    builder.recordOrder(topology.accountLaneId(userId), visibleBefore, visibleAfter,
                            businessBefore, businessAfter);
                }
            });
        }
        for (long positionKey : changedPositions.toArray()) {
            PositionRuntime after = position(positionKey);
            PositionRuntime beforeValue = currentPatchPositionBefore(positionKey);
            long userId = after != null ? after.userId() : beforeValue == null ? 0 : beforeValue.userId();
            if (userId != 0 && !java.util.Objects.equals(beforeValue, after)) {
                builder.recordPosition(topology.accountLaneId(userId), positionKey, beforeValue, after);
            }
        }
        patchLiquidationsBefore.forEach((liquidationId, before) -> {
            LiquidationRuntime after = liquidation(liquidationId);
            LiquidationRuntime beforeValue = before.value();
            long userId = after != null ? after.userId() : beforeValue == null ? 0 : beforeValue.userId();
            if (userId != 0 && !java.util.Objects.equals(beforeValue, after)) {
                LiquidationRuntime value = after != null ? after : beforeValue;
                CoreInstrumentState instrument = instrument(identities.symbol(value.symbolId()));
                if (instrument == null) throw new IllegalStateException("liquidation instrument is missing");
                builder.recordLiquidation(topology.accountLaneId(userId), liquidationId, beforeValue, after,
                        instrument.settleAsset());
            }
        });
        patchRiskSnapshotsBefore.forEach((riskKey, before) -> {
            RiskSnapshotRuntime after = riskSnapshot(riskKey);
            RiskSnapshotRuntime beforeValue = before.value();
            long userId = after != null ? after.userId() : beforeValue == null ? 0 : beforeValue.userId();
            if (userId != 0 && !java.util.Objects.equals(beforeValue, after)) {
                builder.recordRiskSnapshot(topology.accountLaneId(userId), riskKey, beforeValue, after);
            }
        });
        patchLeveragesBefore.forEach((key, before) -> {
            Long after = leverage(key);
            if (!java.util.Objects.equals(before.value(), after)) {
                builder.recordLeverage(topology.accountLaneId(key.userId()), key, before.value(), after);
            }
        });
        patchAlgoOrdersBefore.forEach((id, before) -> {
            CoreAlgoOrderState after = algoOrder(id);
            CoreAlgoOrderState beforeValue = before.value();
            long userId = after != null ? after.userId() : beforeValue == null ? 0 : beforeValue.userId();
            if (userId != 0 && !java.util.Objects.equals(beforeValue, after)) {
                builder.recordAlgoOrder(topology.accountLaneId(userId), id, beforeValue, after);
            }
        });
        patchTriggerOrdersBefore.forEach((id, before) -> {
            CoreTriggerOrderState after = triggerOrder(id);
            CoreTriggerOrderState beforeValue = before.value();
            long userId = after != null ? after.userId() : beforeValue == null ? 0 : beforeValue.userId();
            if (userId != 0 && !java.util.Objects.equals(beforeValue, after)) {
                builder.recordTriggerOrder(topology.accountLaneId(userId), id, beforeValue, after);
            }
        });
        patchClientOrdersBefore.forEach((key, before) -> {
            Long after = orderIdByClient(key.userId(), key.clientKey());
            Long visibleBefore = visibleClientOrder(before.value(), true);
            Long visibleAfter = visibleClientOrder(after, false);
            if (!java.util.Objects.equals(visibleBefore, visibleAfter)) {
                builder.recordClientOrder(topology.accountLaneId(key.userId()), key, visibleBefore, visibleAfter);
            }
        });
        patchTimersBefore.forEach((key, before) -> recordTimerChange(builder, key, before.value(),
                cancelAllAfterTimer(key)));
        patchMarkPricesBefore.forEach((symbolId, before) -> recordMarkPriceChange(builder, symbolId,
                before.value(), markPrice(symbolId)));
        patchRiskScansBefore.forEach((symbolId, before) -> recordRiskScanChange(builder, symbolId,
                before.value(), riskScan(symbolId)));
        patchInstrumentsBefore.forEach((symbol, before) -> recordInstrumentChange(builder, symbol,
                before.value(), instrument(symbol)));
        treasury.changedAssets().forEach(assetId -> recordTreasuryAssetChange(builder, assetId,
                treasury.patchAssetBefore(assetId), treasuryAssetValue(assetId)));
        treasury.changedFundingSymbols().forEach(symbolId -> recordTreasuryFundingChange(builder, symbolId,
                treasury.patchFundingBefore(symbolId), treasuryFundingValue(symbolId)));
        treasury.changedLifecycleSymbols().forEach(symbolId -> recordTreasuryLifecycleChange(builder, symbolId,
                treasury.patchLifecycleBefore(symbolId), treasuryLifecycleValue(symbolId)));
        if (patchNextLiquidationIdChanged) {
            builder.recordNextLiquidationId(patchNextLiquidationIdBefore, nextLiquidationId);
        }
        if (patchRiskScanControlChanged) {
            builder.recordRiskScanControl(patchRiskScanControlBefore, riskScanControl);
        }
        long changedLaneMask = builder.changedLaneMask();
        builder.laneMask(committedLaneMask == 0 ? changedLaneMask : committedLaneMask);
        return new PreparedCommit(builder, identities, new RuntimeCommitPatch.SealMetadata(previousRevision,
                Math.subtractExact(revision, totalPendingReservations), beforeBusinessStateHash,
                businessStateHash, beforeFundsStateHash, fundsStateHash, builder.laneMask(), null,
                externalAdjustment));
    }

    public void abortPreparedCommit(RuntimeCommitPatch sealedPatch) {
        abortPreparedCommitView(sealedPatch);
    }

    public void abortPreparedCommit(RuntimeCommitPatch.PreparedChanges preparedChanges) {
        abortPreparedCommitView(preparedChanges);
    }

    private void abortPreparedCommitView(RuntimeCommitView preparedCommit) {
        assertOwner();
        if (preparedCommit == null) return;
        activePatchBuilder.reset(productLine);
        for (RuntimeCommitPatch.AccountLaneOwnerGroup group : preparedCommit.accountLaneGroups()) {
            for (RuntimeCommitPatch.PositionChange change : group.positions()) {
                activePatchBuilder.capturePositionBefore(group.laneId(), change.positionKey(), change.before());
            }
        }
    }

    public record PreparedCommit(RuntimeCommitPatch.Builder builder, RuntimeIdentityRegistry identities,
                                 RuntimeCommitPatch.SealMetadata metadata) {
        public PreparedCommit {
            if (builder == null || identities == null || metadata == null) {
                throw new IllegalArgumentException("invalid prepared commit");
            }
        }

        public RuntimeCommitPatch.PreparedChanges prepareChanges() {
            return builder.prepare(new RuntimeCommitPatch.PrepareMetadata(
                    metadata.beforeRevision(), metadata.afterRevision(), metadata.beforeBusinessStateHash(),
                    metadata.beforeFundsStateHash(), metadata.laneMask(), metadata.coreFactMetadata(),
                    metadata.externalAdjustment()), identities);
        }

        public RuntimeCommitPatch seal(RuntimeCommitPatch.PreparedChanges changes,
                                       long businessStateHash, long fundsStateHash) {
            return builder.seal(changes, businessStateHash, fundsStateHash);
        }

    }

    private void recordUserChange(RuntimeCommitPatch.Builder builder, long userId,
                                  UserRuntime before, UserRuntime after) {
        if (before == null && after == null) return;
        builder.recordCurrentUser(topology.accountLaneId(userId), before, after,
                after == null ? 0 : pendingReservationCount(userId));
    }

    private void recordTimerChange(RuntimeCommitPatch.Builder builder, CoreCancelAllAfterKey key,
                                   CoreCancelAllAfterState before, CoreCancelAllAfterState after) {
        if (!java.util.Objects.equals(before, after)) {
            builder.recordTimer(topology.accountLaneId(key.userId()), key, before, after);
        }
    }

    private static void recordMarkPriceChange(RuntimeCommitPatch.Builder builder, int symbolId,
                                              MarkPriceRuntime before, MarkPriceRuntime after) {
        if (!java.util.Objects.equals(before, after)) builder.recordMarkPrice(symbolId, before, after);
    }

    private static void recordRiskScanChange(RuntimeCommitPatch.Builder builder, int symbolId,
                                             RiskScanRuntime before, RiskScanRuntime after) {
        if (!java.util.Objects.equals(before, after)) builder.recordRiskScan(symbolId, before, after);
    }

    private static void recordInstrumentChange(RuntimeCommitPatch.Builder builder, String symbol,
                                               CoreInstrumentState before, CoreInstrumentState after) {
        if (!java.util.Objects.equals(before, after)) builder.recordInstrument(symbol, before, after);
    }

    private static void recordTreasuryAssetChange(RuntimeCommitPatch.Builder builder, int assetId,
                                                   RuntimeCommitPatch.TreasuryAssetValue before,
                                                   RuntimeCommitPatch.TreasuryAssetValue after) {
        if (!java.util.Objects.equals(before, after)) builder.recordTreasuryAsset(assetId, before, after);
    }

    private RuntimeCommitPatch.TreasuryFundingValue treasuryFundingValue(int symbolId) {
        long settlementId = treasury.fundingSettlement(symbolId);
        TreasuryRuntime.FundingProgressRuntime progress = treasury.fundingProgress(symbolId);
        return settlementId == 0 && progress == null ? null
                : new RuntimeCommitPatch.TreasuryFundingValue(settlementId, progress);
    }

    private static void recordTreasuryFundingChange(RuntimeCommitPatch.Builder builder, int symbolId,
                                                     RuntimeCommitPatch.TreasuryFundingValue before,
                                                     RuntimeCommitPatch.TreasuryFundingValue after) {
        if (!java.util.Objects.equals(before, after)) builder.recordTreasuryFunding(symbolId, before, after);
    }

    private RuntimeCommitPatch.TreasuryLifecycleValue treasuryLifecycleValue(int symbolId) {
        long settlementId = treasury.lifecycleSettlement(symbolId);
        TreasuryRuntime.LifecycleProgressRuntime progress = treasury.lifecycleProgress(symbolId);
        return settlementId == 0 && progress == null ? null
                : new RuntimeCommitPatch.TreasuryLifecycleValue(settlementId, progress);
    }

    private static void recordTreasuryLifecycleChange(RuntimeCommitPatch.Builder builder, int symbolId,
                                                       RuntimeCommitPatch.TreasuryLifecycleValue before,
                                                       RuntimeCommitPatch.TreasuryLifecycleValue after) {
        if (!java.util.Objects.equals(before, after)) builder.recordTreasuryLifecycle(symbolId, before, after);
    }

    public PositionRuntime currentPatchPositionBefore(long positionKey) {
        assertOwner();
        PositionRuntime current = position(positionKey);
        if (current != null) {
            int laneId = topology.accountLaneId(current.userId());
            return activePatchBuilder.hasPositionCheckpoint(laneId, positionKey)
                    ? activePatchBuilder.positionBefore(laneId, positionKey) : current;
        }
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            if (activePatchBuilder.hasPositionCheckpoint(laneId, positionKey)) {
                return activePatchBuilder.positionBefore(laneId, positionKey);
            }
        }
        return null;
    }

    public OrderRuntime currentPatchOrderBefore(long orderId) {
        assertOwner();
        PatchOrderBefore captured = capturedOrderBefore(orderId);
        return captured == null ? order(orderId) : captured.value();
    }

    private RuntimeCommitPatch.TreasuryAssetValue treasuryAssetValue(int assetId) {
        long fee = treasury.fee(assetId);
        long insurance = treasury.insurance(assetId);
        long deficit = treasury.insuranceDeficit(assetId);
        long liquidationFee = treasury.liquidationFee(assetId);
        long fundingResidual = treasury.fundingResidual(assetId);
        long roundingResidual = treasury.roundingResidual(assetId);
        long clearingPnl = treasury.clearingPnl(assetId);
        if ((fee | insurance | deficit | liquidationFee | fundingResidual | roundingResidual | clearingPnl) == 0) {
            return null;
        }
        return new RuntimeCommitPatch.TreasuryAssetValue(fee, insurance, deficit, liquidationFee,
                fundingResidual, roundingResidual, clearingPnl);
    }

    public void clearChangedKeys() {
        assertOwner();
        clearChanged(changedUsers);
        clearChanged(changedBalances);
        clearChanged(changedOrders);
        clearChanged(changedReservations);
        clearChanged(changedPositions);
        clearChanged(changedLiquidations);
        clearChanged(changedMarkPrices);
        clearChanged(changedRiskSnapshots);
        clearChanged(changedRiskScans);
        clearChanged(changedClientOrders);
        clearChanged(changedClientOrdersByUser);
        changedInstruments.clear();
        changedLeverages.clear();
        clearChanged(changedAlgoOrders);
        changedCancelAllAfterTimers.clear();
        clearChanged(changedTriggerOrders);
        clearChanged(changedFeePolicies);
        treasury.clearChangedKeys();
        for (LongObjectHashMap<UserRuntime> capturedUsers : patchUsersBeforeByLane) {
            clearChanged(capturedUsers);
        }
        for (LaneBalancePatches capturedBalances : patchBalancesBeforeByLane) capturedBalances.clear();
        clearCaptured(patchReservationsBeforeByLane);
        clearCaptured(patchOrdersBeforeByLane);
        activePatchBuilder.reset(productLine);
        patchLiquidationsBefore = clearCapturedChanges(patchLiquidationsBefore);
        patchRiskSnapshotsBefore = clearCapturedChanges(patchRiskSnapshotsBefore);
        patchLeveragesBefore = clearCapturedChanges(patchLeveragesBefore);
        patchAlgoOrdersBefore = clearCapturedChanges(patchAlgoOrdersBefore);
        patchTriggerOrdersBefore = clearCapturedChanges(patchTriggerOrdersBefore);
        patchClientOrdersBefore = clearCapturedChanges(patchClientOrdersBefore);
        patchTimersBefore = clearCapturedChanges(patchTimersBefore);
        patchMarkPricesBefore = clearCapturedChanges(patchMarkPricesBefore);
        patchRiskScansBefore = clearCapturedChanges(patchRiskScansBefore);
        patchInstrumentsBefore = clearCapturedChanges(patchInstrumentsBefore);
        patchPendingTransfersBefore = clearCapturedChanges(patchPendingTransfersBefore);
        patchFeePoliciesBefore = clearCapturedChanges(patchFeePoliciesBefore);
        patchNextLiquidationIdChanged = false;
        patchRiskScanControlChanged = false;
    }

    private static void clearChanged(LongHashSet values) {
        boolean compact = values.size() >= CHANGE_KEY_COMPACTION_THRESHOLD;
        values.clear();
        if (compact) values.compact();
    }

    private static void clearChanged(IntHashSet values) {
        boolean compact = values.size() >= CHANGE_KEY_COMPACTION_THRESHOLD;
        values.clear();
        if (compact) values.compact();
    }

    private static <T> void clearChanged(LongObjectHashMap<T> values) {
        boolean compact = values.size() >= CHANGE_KEY_COMPACTION_THRESHOLD;
        values.clear();
        if (compact) values.compact();
    }

    static <K, V> ConcurrentHashMap<K, V> clearCapturedChanges(ConcurrentHashMap<K, V> values) {
        if (values.size() >= CHANGE_KEY_COMPACTION_THRESHOLD) return new ConcurrentHashMap<>();
        values.clear();
        return values;
    }

    TradingRuntimeSnapshot snapshot(long revision) {
        assertOwner();
        return RuntimeSnapshotBuilder.capture(this, revision);
    }

    LongObjectHashMap<UserRuntime> usersForSnapshot() {
        LongObjectHashMap<UserRuntime> values = new LongObjectHashMap<>();
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            values.putAll(onLane(laneId, lane -> new LongObjectHashMap<>(lane.users)));
        }
        return values;
    }

    LongObjectHashMap<IntObjectHashMap<BalanceRuntime>> balancesForSnapshot() {
        LongObjectHashMap<IntObjectHashMap<BalanceRuntime>> values = new LongObjectHashMap<>();
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            values.putAll(onLane(laneId, lane -> copyAllBalances(lane.balances)));
        }
        return values;
    }

    LongObjectHashMap<OrderRuntime> ordersForSnapshot() {
        LongObjectHashMap<OrderRuntime> values = new LongObjectHashMap<>();
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            values.putAll(onLane(laneId, lane -> new LongObjectHashMap<>(lane.orders)));
        }
        return values;
    }

    List<OrderRuntime> ordersForUser(long userId) {
        assertOwner();
        ArrayList<OrderRuntime> values = new ArrayList<>();
        ReservedOrder reserved = onLane(userId, lane -> {
            lane.orders.forEachValue(order -> {
                if (order.userId() == userId) values.add(order);
            });
            return null;
        });
        return List.copyOf(values);
    }

    LongObjectHashMap<ReservationRuntime> reservationsForSnapshot() {
        LongObjectHashMap<ReservationRuntime> values = new LongObjectHashMap<>();
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            values.putAll(onLane(laneId, lane -> new LongObjectHashMap<>(lane.reservations)));
        }
        return values;
    }

    LongObjectHashMap<LongObjectHashMap<Long>> clientOrderIndexForSnapshot() {
        LongObjectHashMap<LongObjectHashMap<Long>> values = new LongObjectHashMap<>();
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            values.putAll(onLane(laneId, lane -> copyClientOrderIndex(lane.clientOrderIndex)));
        }
        return values;
    }

    LongObjectHashMap<PositionRuntime> positionsForSnapshot() {
        LongObjectHashMap<PositionRuntime> values = new LongObjectHashMap<>();
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            values.putAll(onLane(laneId, lane -> new LongObjectHashMap<>(lane.positions)));
        }
        return values;
    }

    LongObjectHashMap<LiquidationRuntime> liquidationsForSnapshot() {
        LongObjectHashMap<LiquidationRuntime> values = new LongObjectHashMap<>();
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            values.putAll(onLane(laneId, lane -> new LongObjectHashMap<>(lane.liquidations)));
        }
        return values;
    }

    IntObjectHashMap<MarkPriceRuntime> markPricesForSnapshot() {
        return markPrices;
    }

    LongObjectHashMap<RiskSnapshotRuntime> riskSnapshotsForSnapshot() {
        LongObjectHashMap<RiskSnapshotRuntime> values = new LongObjectHashMap<>();
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            values.putAll(onLane(laneId, lane -> new LongObjectHashMap<>(lane.riskSnapshots)));
        }
        return values;
    }

    IntObjectHashMap<RiskScanRuntime> riskScansForSnapshot() {
        return riskScans;
    }

    public void reserveOrder(long orderId, long userId, long clientKey, int symbolId,
                             long quantitySteps, int assetId, long reservedUnits) {
        assertOwner();
        if (order(orderId) != null) {
            throw new IllegalArgumentException("runtime order already exists: " + orderId);
        }
        captureUserBefore(userId);
        captureOrderBefore(orderId);
        captureReservationBefore(orderId);
        captureBalanceBefore(userId, assetId);
        if (clientKey != 0) captureClientOrderBefore(userId, clientKey);
        ReservedOrder reserved = onLane(userId, lane -> {
            LongObjectHashMap<Long> userClientOrders = lane.clientOrderIndex.get(userId);
            if (clientKey != 0 && userClientOrders != null && userClientOrders.containsKey(clientKey)) {
                throw new IllegalArgumentException("runtime client order already exists: " + clientKey);
            }
            UserRuntime user = lane.users.get(userId);
            if (user == null) throw new IllegalArgumentException("runtime user is not registered: " + userId);
            IntObjectHashMap<BalanceRuntime> balances = lane.balances.get(userId);
            BalanceRuntime balance = balances == null ? null : balances.get(assetId);
            if (balance == null) {
                throw new IllegalArgumentException("runtime balance is not registered: " + userId + "/" + assetId);
            }
            OrderRuntime order = new OrderRuntime(orderId, userId, symbolId, quantitySteps);
            ReservationRuntime reservation = new ReservationRuntime(orderId, userId, assetId, reservedUnits);
            balance.reserve(reservedUnits);
            lane.orders.put(orderId, order);
            lane.reservations.put(orderId, reservation);
            addUserEntity(lane.reservationIdsByUser, userId, orderId);
            if (clientKey != 0) putClientOrderIndex(lane, userId, clientKey, orderId);
            return new ReservedOrder(order, reservation);
        });
        publishOrder(orderId, reserved.order());
        publishReservation(orderId, reserved.reservation());
        orderLaneIds.put(orderId, topology.accountLaneId(userId) + 1L);
        reservationLaneIds.put(orderId, topology.accountLaneId(userId) + 1L);
        changedOrders.add(orderId);
        changedReservations.add(orderId);
        if (clientKey != 0) {
            changedClientOrders.add(clientKey);
            changedClientOrder(userId, clientKey);
        }
        changedUsers.add(userId);
        changedBalance(userId, assetId);
    }

    public void putPosition(long positionKey, PositionRuntime position) {
        assertOwner();
        capturePositionBefore(positionKey, position.userId());
        PositionRuntime previous = position(positionKey);
        if (previous != null && previous.userId() != position.userId()) {
            throw new IllegalArgumentException("runtime position owner cannot change");
        }
        onLane(position.userId(), lane -> {
            if (previous != null) unindexPosition(lane, positionKey, previous);
            lane.positions.put(positionKey, position);
            indexPosition(lane, positionKey, position);
            return null;
        });
        publishPosition(positionKey, position);
        positionLaneIds.put(positionKey, topology.accountLaneId(position.userId()) + 1L);
        changedPositions.add(positionKey);
        changedUsers.add(position.userId());
        if (previous != null) changedUsers.add(previous.userId());
    }

    private static void indexPosition(AccountLaneState lane, long positionKey, PositionRuntime position) {
        addUserEntity(lane.positionKeysByUser, position.userId(), positionKey);
        if (position.signedQuantitySteps() == 0) return;
        LongObjectHashMap<TreeSet<Long>> byUser = lane.positionKeysBySymbolAndUser.get(position.symbolId());
        if (byUser == null) {
            byUser = new LongObjectHashMap<>();
            lane.positionKeysBySymbolAndUser.put(position.symbolId(), byUser);
        }
        TreeSet<Long> keys = byUser.get(position.userId());
        if (keys == null) {
            keys = new TreeSet<>();
            byUser.put(position.userId(), keys);
        }
        keys.add(positionKey);
    }

    private static void unindexPosition(AccountLaneState lane, long positionKey, PositionRuntime position) {
        removeUserEntity(lane.positionKeysByUser, position.userId(), positionKey);
        LongObjectHashMap<TreeSet<Long>> byUser = lane.positionKeysBySymbolAndUser.get(position.symbolId());
        TreeSet<Long> keys = byUser == null ? null : byUser.get(position.userId());
        if (keys == null || !keys.remove(positionKey)) return;
        if (keys.isEmpty()) byUser.remove(position.userId());
        if (byUser.isEmpty()) lane.positionKeysBySymbolAndUser.remove(position.symbolId());
    }

    private void changedBalance(long userId, int assetId) {
        IntHashSet assets = changedBalances.get(userId);
        if (assets == null) {
            assets = new IntHashSet();
            changedBalances.put(userId, assets);
        }
        assets.add(assetId);
    }

    private void captureUserBefore(long userId) {
        LongObjectHashMap<UserRuntime> captured =
                patchUsersBeforeByLane[topology.accountLaneId(userId)];
        if (!captured.containsKey(userId)) captured.put(userId, user(userId));
    }

    private void rollbackUser(long userId, UserRuntime before) {
        onLane(userId, lane -> {
            if (before == null) {
                lane.users.remove(userId);
                lane.removeUser(userId);
            } else {
                lane.users.put(userId, before);
                lane.registerUser(userId);
            }
            return null;
        });
        publishUser(userId, before);
    }

    private void rollbackBalance(long userId, int assetId, RuntimeCommitPatch.UserBalance before) {
        onLane(userId, lane -> {
            IntObjectHashMap<BalanceRuntime> balances = lane.balances.get(userId);
            if (before == null) {
                if (balances != null) {
                    balances.remove(assetId);
                    if (balances.isEmpty()) lane.balances.remove(userId);
                }
            } else {
                if (balances == null) {
                    balances = new IntObjectHashMap<>();
                    lane.balances.put(userId, balances);
                }
                balances.put(assetId, new BalanceRuntime(userId, assetId,
                        before.availableUnits(), before.lockedUnits()));
            }
            return null;
        });
    }

    private void rollbackOrder(long orderId, OrderRuntime before) {
        OrderRuntime current = order(orderId);
        if (current != null) onLane(current.userId(), lane -> { lane.orders.remove(orderId); return null; });
        if (before == null) {
            publishOrder(orderId, null);
            orderLaneIds.removeKey(orderId);
        } else {
            onLane(before.userId(), lane -> { lane.orders.put(orderId, before); return null; });
            publishOrder(orderId, before);
            orderLaneIds.put(orderId, topology.accountLaneId(before.userId()) + 1L);
        }
    }

    private void rollbackReservation(long orderId, ReservationRuntime before) {
        ReservationRuntime current = reservation(orderId);
        if (current != null) onLane(current.userId(), lane -> {
            lane.reservations.remove(orderId);
            removeUserEntity(lane.reservationIdsByUser, current.userId(), orderId);
            return null;
        });
        if (before == null) {
            publishReservation(orderId, null);
            reservationLaneIds.removeKey(orderId);
        } else {
            onLane(before.userId(), lane -> {
                lane.reservations.put(orderId, before);
                addUserEntity(lane.reservationIdsByUser, before.userId(), orderId);
                return null;
            });
            publishReservation(orderId, before);
            reservationLaneIds.put(orderId, topology.accountLaneId(before.userId()) + 1L);
        }
    }

    private void rollbackPosition(long positionKey) {
        PositionRuntime current = position(positionKey);
        int laneId = current == null ? -1 : topology.accountLaneId(current.userId());
        PositionRuntime before = laneId >= 0 && activePatchBuilder.hasPositionCheckpoint(laneId, positionKey)
                ? activePatchBuilder.positionBefore(laneId, positionKey) : null;
        if (current == null) {
            for (int candidateLaneId = 0; candidateLaneId < accountLanes.length; candidateLaneId++) {
                if (activePatchBuilder.hasPositionCheckpoint(candidateLaneId, positionKey)) {
                    before = activePatchBuilder.positionBefore(candidateLaneId, positionKey);
                    break;
                }
            }
        }
        PositionRuntime rollbackValue = before;
        if (current != null) onLane(current.userId(), lane -> {
            lane.positions.remove(positionKey);
            unindexPosition(lane, positionKey, current);
            return null;
        });
        if (rollbackValue == null) {
            publishPosition(positionKey, null);
            positionLaneIds.removeKey(positionKey);
        } else {
            onLane(rollbackValue.userId(), lane -> {
                lane.positions.put(positionKey, rollbackValue);
                indexPosition(lane, positionKey, rollbackValue);
                return null;
            });
            publishPosition(positionKey, rollbackValue);
            positionLaneIds.put(positionKey, topology.accountLaneId(rollbackValue.userId()) + 1L);
        }
    }

    private void captureBalanceBefore(long userId, int assetId) {
        LaneBalancePatches captured = patchBalancesBeforeByLane[topology.accountLaneId(userId)];
        if (captured.contains(userId, assetId)) return;
        BalanceRuntime balance = balance(userId, assetId);
        captured.add(userId, assetId, balance == null ? null : new RuntimeCommitPatch.UserBalance(
                balance.availableUnits(), balance.lockedUnits(), pendingReservedUnits(userId, assetId)));
    }

    private void captureOrderBefore(long orderId) {
        if (capturedOrderBefore(orderId) != null) return;
        OrderRuntime value = order(orderId);
        int laneId = captureLane(orderId, value == null ? 0 : value.userId(), orderLaneIds);
        LongObjectHashMap<PatchOrderBefore> captured = patchOrdersBeforeByLane[laneId];
        if (!captured.containsKey(orderId)) {
            captured.put(orderId, new PatchOrderBefore(value,
                    value != null && pendingReservation(orderId, value.userId())));
        }
    }

    private void captureReservationBefore(long orderId) {
        if (capturedReservationBefore(orderId) != null) return;
        ReservationRuntime value = reservation(orderId);
        int laneId = captureLane(orderId, value == null ? 0 : value.userId(), reservationLaneIds);
        LongObjectHashMap<PatchReservationBefore> captured = patchReservationsBeforeByLane[laneId];
        if (!captured.containsKey(orderId)) {
            captured.put(orderId, new PatchReservationBefore(value,
                    value != null && pendingReservation(orderId, value.userId())));
        }
    }

    private boolean reservationPendingBefore(long orderId) {
        PatchReservationBefore before = capturedReservationBefore(orderId);
        return before != null && before.pending();
    }

    private int captureLane(long entityId, long userId, LongLongHashMap laneIds) {
        AccountLaneState scoped = laneCommandScope.get();
        if (scoped != null) return scoped.laneId();
        if (userId > 0) return topology.accountLaneId(userId);
        int indexed = indexedLane(laneIds, entityId);
        return indexed >= 0 ? indexed : Math.floorMod(Long.hashCode(entityId), accountLanes.length);
    }

    private PatchOrderBefore capturedOrderBefore(long orderId) {
        for (LongObjectHashMap<PatchOrderBefore> captured : patchOrdersBeforeByLane) {
            PatchOrderBefore value = captured.get(orderId);
            if (value != null || captured.containsKey(orderId)) return value;
        }
        return null;
    }

    private PatchReservationBefore capturedReservationBefore(long orderId) {
        for (LongObjectHashMap<PatchReservationBefore> captured : patchReservationsBeforeByLane) {
            PatchReservationBefore value = captured.get(orderId);
            if (value != null || captured.containsKey(orderId)) return value;
        }
        return null;
    }

    private static void clearCaptured(LongObjectHashMap<?>[] capturedByLane) {
        for (LongObjectHashMap<?> captured : capturedByLane) {
            boolean compact = captured.size() >= CHANGE_KEY_COMPACTION_THRESHOLD;
            captured.clear();
            if (compact) captured.compact();
        }
    }

    private boolean reservationPendingAfter(long orderId) {
        ReservationRuntime current = reservation(orderId);
        return current != null && pendingReservation(orderId, current.userId());
    }

    private Long visibleClientOrder(Long orderId, boolean before) {
        if (orderId == null) return null;
        boolean pending = before ? reservationPendingBefore(orderId) : reservationPendingAfter(orderId);
        return pending ? null : orderId;
    }

    private void capturePositionBefore(long positionKey) {
        capturePositionBefore(positionKey, 0);
    }

    private void capturePositionBefore(long positionKey, long fallbackUserId) {
        PositionRuntime before = position(positionKey);
        long userId = before == null ? fallbackUserId : before.userId();
        if (userId > 0) {
            activePatchBuilder.capturePositionBefore(topology.accountLaneId(userId), positionKey, before);
        }
    }

    private void captureLiquidationBefore(long liquidationId) {
        rejectUnsupportedOrderBatchMutation("liquidation state");
        patchLiquidationsBefore.computeIfAbsent(liquidationId, id -> new PatchBefore<>(liquidation(id)));
    }

    private void captureRiskSnapshotBefore(long positionKey) {
        rejectUnsupportedOrderBatchMutation("risk snapshot state");
        patchRiskSnapshotsBefore.computeIfAbsent(positionKey, key -> new PatchBefore<>(riskSnapshot(key)));
    }

    private void captureMarkPriceBefore(int symbolId) {
        rejectUnsupportedOrderBatchMutation("mark-price state");
        patchMarkPricesBefore.computeIfAbsent(symbolId, id -> new PatchBefore<>(markPrices.get(id)));
    }

    private void captureRiskScanBefore(int symbolId) {
        rejectUnsupportedOrderBatchMutation("risk-scan state");
        patchRiskScansBefore.computeIfAbsent(symbolId, id -> new PatchBefore<>(riskScans.get(id)));
    }

    private void captureClientOrderBefore(long userId, long clientKey) {
        RuntimeCommitPatch.ClientOrderKey key = new RuntimeCommitPatch.ClientOrderKey(userId, clientKey);
        patchClientOrdersBefore.computeIfAbsent(key,
                ignored -> new PatchBefore<>(orderIdByClient(userId, clientKey)));
    }

    private static void addUserEntity(LongObjectHashMap<LongHashSet> index, long userId, long entityId) {
        LongHashSet entities = index.get(userId);
        if (entities == null) {
            entities = new LongHashSet();
            index.put(userId, entities);
        }
        entities.add(entityId);
    }

    private static void removeUserEntity(LongObjectHashMap<LongHashSet> index, long userId, long entityId) {
        LongHashSet entities = index.get(userId);
        if (entities == null) return;
        entities.remove(entityId);
        if (entities.isEmpty()) index.remove(userId);
    }

    private static void putClientOrderIndex(AccountLaneState lane, long userId, long clientKey, long orderId) {
        LongObjectHashMap<Long> userClientOrders = lane.clientOrderIndex.get(userId);
        if (userClientOrders == null) {
            userClientOrders = new LongObjectHashMap<>();
            lane.clientOrderIndex.put(userId, userClientOrders);
        }
        Long previousOrderId = userClientOrders.put(clientKey, orderId);
        if (previousOrderId != null && previousOrderId != orderId) {
            removeClientOrderReverse(lane, previousOrderId, clientKey);
        }
        LongHashSet keys = lane.clientKeysByOrderId.get(orderId);
        if (keys == null) {
            keys = new LongHashSet();
            lane.clientKeysByOrderId.put(orderId, keys);
        }
        keys.add(clientKey);
    }

    private static Long removeClientOrderIndex(AccountLaneState lane, long userId, long clientKey) {
        LongObjectHashMap<Long> userClientOrders = lane.clientOrderIndex.get(userId);
        if (userClientOrders == null) return null;
        Long orderId = userClientOrders.remove(clientKey);
        if (userClientOrders.isEmpty()) lane.clientOrderIndex.remove(userId);
        if (orderId != null) removeClientOrderReverse(lane, orderId, clientKey);
        return orderId;
    }

    private static void removeClientOrderReverse(AccountLaneState lane, long orderId, long clientKey) {
        LongHashSet keys = lane.clientKeysByOrderId.get(orderId);
        if (keys == null) return;
        keys.remove(clientKey);
        if (keys.isEmpty()) lane.clientKeysByOrderId.remove(orderId);
    }

    private static LongHashSet clientKeysForOrder(AccountLaneState lane, long orderId) {
        LongHashSet keys = lane.clientKeysByOrderId.get(orderId);
        return keys == null ? new LongHashSet() : new LongHashSet(keys);
    }

    private void changedClientOrder(long userId, long clientKey) {
        LongHashSet keys = changedClientOrdersByUser.get(userId);
        if (keys == null) {
            keys = new LongHashSet();
            changedClientOrdersByUser.put(userId, keys);
        }
        keys.add(clientKey);
    }

    private static void indexActiveLiquidation(AccountLaneState lane, LiquidationRuntime liquidation) {
        if (!active(liquidation)) return;
        IntObjectHashMap<LongObjectHashMap<Long>> bySymbol = lane.activeLiquidationIndex.get(liquidation.userId());
        if (bySymbol == null) {
            bySymbol = new IntObjectHashMap<>();
            lane.activeLiquidationIndex.put(liquidation.userId(), bySymbol);
        }
        LongObjectHashMap<Long> bySide = bySymbol.get(liquidation.symbolId());
        if (bySide == null) {
            bySide = new LongObjectHashMap<>();
            bySymbol.put(liquidation.symbolId(), bySide);
        }
        Long current = bySide.get(liquidation.positionSide().ordinal());
        if (current != null && current != liquidation.liquidationId()) {
            throw new IllegalStateException("multiple active runtime liquidations for one position");
        }
        bySide.put(liquidation.positionSide().ordinal(), liquidation.liquidationId());
    }

    private static void removeActiveLiquidation(AccountLaneState lane, LiquidationRuntime liquidation) {
        if (!active(liquidation)) return;
        IntObjectHashMap<LongObjectHashMap<Long>> bySymbol = lane.activeLiquidationIndex.get(liquidation.userId());
        LongObjectHashMap<Long> bySide = bySymbol == null ? null : bySymbol.get(liquidation.symbolId());
        if (bySide == null) return;
        bySide.remove(liquidation.positionSide().ordinal());
        if (bySide.isEmpty()) bySymbol.remove(liquidation.symbolId());
        if (bySymbol.isEmpty()) lane.activeLiquidationIndex.remove(liquidation.userId());
    }

    private static boolean active(LiquidationRuntime liquidation) {
        return liquidation.status() != CoreLiquidationState.Status.COMPLETED
                && liquidation.status() != CoreLiquidationState.Status.CANCELED;
    }

    private static final class LaneBalancePatches {
        private long[] userIds = new long[8];
        private int[] assetIds = new int[8];
        private RuntimeCommitPatch.UserBalance[] before = new RuntimeCommitPatch.UserBalance[8];
        private int size;

        private int size() { return size; }
        private long userId(int index) { return userIds[index]; }
        private int assetId(int index) { return assetIds[index]; }
        private RuntimeCommitPatch.UserBalance before(int index) { return before[index]; }

        private boolean contains(long userId, int assetId) {
            for (int index = 0; index < size; index++) {
                if (userIds[index] == userId && assetIds[index] == assetId) return true;
            }
            return false;
        }

        private void add(long userId, int assetId, RuntimeCommitPatch.UserBalance value) {
            if (size == userIds.length) {
                int capacity = Math.multiplyExact(size, 2);
                userIds = java.util.Arrays.copyOf(userIds, capacity);
                assetIds = java.util.Arrays.copyOf(assetIds, capacity);
                before = java.util.Arrays.copyOf(before, capacity);
            }
            userIds[size] = userId;
            assetIds[size] = assetId;
            before[size] = value;
            size++;
        }

        private void clear() {
            java.util.Arrays.fill(before, 0, size, null);
            size = 0;
        }
    }

    private record PatchBefore<T>(T value) {}
    private record PatchOrderBefore(OrderRuntime value, boolean pending) {}
    private record PatchReservationBefore(ReservationRuntime value, boolean pending) {}

}
