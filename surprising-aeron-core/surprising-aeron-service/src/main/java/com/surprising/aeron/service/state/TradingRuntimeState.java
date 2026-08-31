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
    private static final int PARALLEL_LIFECYCLE_MIN_ITEMS = Math.max(2,
            Integer.getInteger("surprising.aeron.parallel-lifecycle-min-items", 2));

    private ProductLine productLine = ProductLine.LINEAR_PERPETUAL;
    private long revision;
    private final LaneTopology topology;
    private final AccountLaneState[] accountLanes;
    private final ArrayList<Long>[] laneUserScratch;
    private final SettlementLaneWorker[] lifecycleLaneWorkers;
    private final LifecycleLaneTask[] lifecycleLaneTasks;
    private final boolean[] lifecycleSelectedScratch;
    private final long[] lifecycleStartedNanosScratch;
    private final SettlementLaneWorker[] settlementLaneWorkers;
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
    private final ConcurrentHashMap<Long, UserRuntime> publishedUsers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, OrderRuntime> publishedOrders = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, ReservationRuntime> publishedReservations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, PositionRuntime> publishedPositions = new ConcurrentHashMap<>();
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
    private ConcurrentHashMap<Long, PatchBefore<UserRuntime>> patchUsersBefore = new ConcurrentHashMap<>();
    private ConcurrentHashMap<RuntimeCommitPatch.BalanceKey, PatchBefore<RuntimeCommitPatch.UserBalance>>
            patchBalancesBefore = new ConcurrentHashMap<>();
    private ConcurrentHashMap<Long, PatchReservationBefore> patchReservationsBefore =
            new ConcurrentHashMap<>();
    private ConcurrentHashMap<Long, PatchOrderBefore> patchOrdersBefore =
            new ConcurrentHashMap<>();
    private RuntimeCommitPatch.Builder activePatchBuilder = RuntimeCommitPatch.builder(productLine);
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
        ArrayList<Long>[] routedUsers = new ArrayList[topology.accountLaneCount()];
        this.laneUserScratch = routedUsers;
        this.lifecycleLaneWorkers = new SettlementLaneWorker[topology.accountLaneCount()];
        this.lifecycleLaneTasks = new LifecycleLaneTask[topology.accountLaneCount()];
        this.lifecycleSelectedScratch = new boolean[topology.accountLaneCount()];
        this.lifecycleStartedNanosScratch = new long[topology.accountLaneCount()];
        this.settlementLaneWorkers = new SettlementLaneWorker[topology.accountLaneCount()];
        this.accountLaneQueueHighWaterMarks = new int[topology.accountLaneCount()];
        this.accountLaneCompletedOperations = laneMetricValues(topology.accountLaneCount());
        this.accountLaneLatencySamples = laneMetricValues(topology.accountLaneCount());
        this.accountLaneTotalLatencyNanos = laneMetricValues(topology.accountLaneCount());
        this.accountLaneMaxLatencyNanos = laneMetricValues(topology.accountLaneCount());
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            accountLanes[laneId] = new AccountLaneState(laneId, topology.accountLaneQueueCapacity());
            laneUserScratch[laneId] = new ArrayList<>(4);
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
        }
    }

    public <T> T executeUserSettlement(long userId, java.util.function.Supplier<T> operation) {
        assertOwner();
        if (userId <= 0 || operation == null) {
            throw new IllegalArgumentException("invalid user settlement command");
        }
        return onLane(topology.accountLaneId(userId),
                lane -> inLaneCommandScope(lane, ignored -> operation.get()));
    }

    public <T> T executeUserRisk(long userId, java.util.function.Supplier<T> operation) {
        assertOwner();
        if (userId <= 0 || operation == null) {
            throw new IllegalArgumentException("invalid user risk command");
        }
        return onLane(topology.accountLaneId(userId),
                lane -> inLaneCommandScope(lane, ignored -> operation.get()));
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
        boolean[] selected = new boolean[accountLanes.length];
        for (E value : values) {
            if (value == null) continue;
            long userId = ownerUserId.applyAsLong(value);
            if (userId > 0) selected[topology.accountLaneId(userId)] = true;
        }
        Object[] results = new Object[accountLanes.length];
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            if (!selected[laneId]) continue;
            int currentLaneId = laneId;
            results[laneId] = inLaneCommandScope(accountLanes[laneId],
                    ignored -> operation.apply(currentLaneId));
        }
        return results;
    }

    public <E> Object[] executeLifecycleSettlements(Iterable<E> values,
                                                    java.util.function.ToLongFunction<E> ownerUserId,
                                                    java.util.function.IntFunction<Object> operation) {
        assertOwner();
        if (values == null || ownerUserId == null || operation == null) {
            throw new IllegalArgumentException("invalid lifecycle settlement command");
        }
        boolean[] selected = lifecycleSelectedScratch;
        java.util.Arrays.fill(selected, false);
        int selectedCount = 0;
        int workItems = 0;
        for (E value : values) {
            if (value == null) continue;
            long userId = ownerUserId.applyAsLong(value);
            if (userId <= 0) continue;
            workItems++;
            int laneId = topology.accountLaneId(userId);
            if (!selected[laneId]) {
                selected[laneId] = true;
                selectedCount++;
            }
        }
        if (selectedCount < 2 || workItems < PARALLEL_LIFECYCLE_MIN_ITEMS || !accountLanesStarted) {
            return executeOwnerSettlements(values, ownerUserId, operation);
        }
        Object[] results = new Object[accountLanes.length];
        long[] startedNanos = lifecycleStartedNanosScratch;
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            if (!selected[laneId]) continue;
            AccountLaneState lane = accountLanes[laneId];
            lane.releaseOwner();
            accountLaneQueueHighWaterMarks[laneId] = Math.max(accountLaneQueueHighWaterMarks[laneId], 1);
            startedNanos[laneId] = System.nanoTime();
            LifecycleLaneTask task = lifecycleLaneTasks[laneId];
            if (task == null) {
                task = new LifecycleLaneTask(laneId);
                lifecycleLaneTasks[laneId] = task;
            }
            SettlementLaneWorker worker = lifecycleLaneWorkers[laneId];
            if (worker == null) {
                worker = new SettlementLaneWorker("lifecycle", laneId, topology.accountLaneQueueCapacity());
                lifecycleLaneWorkers[laneId] = worker;
            }
            task.prepare(lane, operation);
            try {
                worker.execute(task);
            } catch (RuntimeException failure) {
                task.failSubmission(failure);
                lane.bindOwner();
                awaitAndRebindLifecycleLanes(selected);
                throw failure;
            }
        }
        RuntimeException failure = null;
        for (int laneId = 0; laneId < lifecycleLaneTasks.length; laneId++) {
            if (!selected[laneId]) continue;
            try {
                results[laneId] = lifecycleLaneTasks[laneId].await();
                recordLaneOperation(laneId, AccountLaneOperationType.SETTLEMENT,
                        System.nanoTime() - startedNanos[laneId]);
            } catch (RuntimeException laneFailure) {
                failure = laneFailure;
                break;
            }
        }
        awaitAndRebindLifecycleLanes(selected);
        if (failure != null) throw failure;
        return results;
    }

    private void awaitAndRebindLifecycleLanes(boolean[] selected) {
        for (int laneId = 0; laneId < selected.length; laneId++) {
            if (!selected[laneId]) continue;
            try {
                lifecycleLaneTasks[laneId].await();
            } catch (RuntimeException ignored) {
            }
        }
        for (int laneId = 0; laneId < selected.length; laneId++) {
            if (selected[laneId]) accountLanes[laneId].bindOwner();
        }
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

    private static void applyLaneUsers(AccountLaneState lane, java.util.List<Long> users, long coreSequence,
                                       long stateContribution, long fundsContribution) {
        for (long userId : users) {
            if (!lane.owns(userId)) lane.registerUser(userId);
            lane.applied(coreSequence, userId, stateContribution, fundsContribution);
        }
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

    @Override
    public void close() {
        accountLanesStarted = false;
        for (SettlementLaneWorker worker : lifecycleLaneWorkers) {
            if (worker != null) worker.close();
        }
        for (SettlementLaneWorker worker : settlementLaneWorkers) {
            if (worker != null) worker.close();
        }
    }

    private final class LifecycleLaneTask implements Runnable {
        private final int laneId;
        private final LaneOperation<Object> scopedOperation;
        private AccountLaneState lane;
        private java.util.function.IntFunction<Object> operation;
        private Object result;
        private Throwable failure;
        private volatile boolean completed = true;
        private volatile Thread waiter;

        private LifecycleLaneTask(int laneId) {
            this.laneId = laneId;
            scopedOperation = ignored -> operation.apply(this.laneId);
        }

        private void prepare(AccountLaneState lane, java.util.function.IntFunction<Object> operation) {
            if (!completed) throw new IllegalStateException("lifecycle lane task is still active");
            this.lane = lane;
            this.operation = operation;
            result = null;
            failure = null;
            completed = false;
        }

        @Override
        public void run() {
            try {
                result = inLaneCommandScope(lane, scopedOperation);
            } catch (Throwable taskFailure) {
                failure = taskFailure;
            } finally {
                lane.releaseOwner();
                completed = true;
                Thread blocked = waiter;
                if (blocked != null) java.util.concurrent.locks.LockSupport.unpark(blocked);
            }
        }

        private Object await() {
            boolean interrupted = false;
            Thread current = Thread.currentThread();
            waiter = current;
            try {
                while (!completed) {
                    java.util.concurrent.locks.LockSupport.park(this);
                    if (Thread.interrupted()) interrupted = true;
                }
            } finally {
                if (waiter == current) waiter = null;
                if (interrupted) Thread.currentThread().interrupt();
            }
            if (interrupted) {
                throw new IllegalStateException("lifecycle lane settlement was interrupted");
            }
            if (failure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
            if (failure != null) throw new IllegalStateException("lifecycle lane settlement failed", failure);
            return result;
        }

        private void failSubmission(Throwable submissionFailure) {
            failure = submissionFailure;
            completed = true;
        }
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

    public List<RuntimeCommitPatch.LaneCommit> applyAndCommitLaneSequence(
            long coreSequence, Iterable<Long> userIds, CoreMatchingResult matchingResult,
            long stateContribution, long fundsContribution) {
        assertOwner();
        if (coreSequence <= 0 || userIds == null || matchingResult == null
                || matchingResult.nativeCommand().coreSequence() != coreSequence) {
            throw new IllegalArgumentException("invalid lane apply");
        }
        for (ArrayList<Long> users : laneUserScratch) users.clear();
        for (Long userId : userIds) {
            if (userId == null || userId <= 0) continue;
            int laneId = topology.accountLaneId(userId);
            laneUserScratch[laneId].add(userId);
        }
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            if (!laneUserScratch[laneId].isEmpty()) accountLanes[laneId].requireApplySequence(coreSequence);
        }
        ArrayList<RuntimeCommitPatch.LaneCommit> laneCommits = new ArrayList<>();
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            java.util.List<Long> users = laneUserScratch[laneId];
            if (users.isEmpty()) continue;
            AccountLaneState lane = accountLanes[laneId];
            if (matchingResult.nativeCommand().coreSequence() != coreSequence) {
                throw new IllegalStateException("immutable matcher result sequence changed during fanout");
            }
            long beforeRevision = lane.revision();
            long beforeStateHash = lane.localStateHash();
            long beforeFundsHash = lane.localFundsHash();
            applyLaneUsers(lane, users, coreSequence, stateContribution, fundsContribution);
            RuntimeCommitPatch.LaneCommit laneCommit = new RuntimeCommitPatch.LaneCommit(
                    laneId, coreSequence, coreSequence, laneCommits.size(), laneCommits.size() + 1,
                    beforeRevision, lane.revision(), beforeStateHash, lane.localStateHash(),
                    beforeFundsHash, lane.localFundsHash());
            activePatchBuilder.addLaneCommit(laneCommit);
            laneCommits.add(laneCommit);
        }
        requireLaneCommitSequence(laneCommits);
        return List.copyOf(laneCommits);
    }

    public void commitLaneSequence(List<RuntimeCommitPatch.LaneCommit> laneCommits) {
        assertOwner();
        requireLaneCommitSequence(laneCommits);
        for (RuntimeCommitPatch.LaneCommit laneCommit : laneCommits) {
            AccountLaneState lane = accountLanes[laneCommit.laneId()];
            lane.committed(laneCommit.committedSequence());
            if (lane.appliedSequence() != laneCommit.appliedSequence()
                    || lane.committedSequence() != laneCommit.committedSequence()) {
                throw new IllegalStateException("account lane read fence differs from lane commit");
            }
        }
    }

    public void rollbackLaneSequence(List<RuntimeCommitPatch.LaneCommit> laneCommits) {
        assertOwner();
        if (laneCommits == null) return;
        for (RuntimeCommitPatch.LaneCommit laneCommit : laneCommits) {
            accountLanes[laneCommit.laneId()].rollbackApplied(laneCommit.appliedSequence());
        }
    }

    private void requireLaneCommitSequence(List<RuntimeCommitPatch.LaneCommit> laneCommits) {
        if (laneCommits == null) throw new IllegalArgumentException("lane commits are required");
        int previousLaneId = -1;
        for (RuntimeCommitPatch.LaneCommit laneCommit : laneCommits) {
            if (laneCommit.laneId() <= previousLaneId || laneCommit.laneId() >= accountLanes.length) {
                throw new IllegalArgumentException("lane commits must be unique and sorted");
            }
            accountLanes[laneCommit.laneId()].requireCommit(laneCommit.appliedSequence());
            previousLaneId = laneCommit.laneId();
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
        RuntimeTreasuryDelta[] deltas = new RuntimeTreasuryDelta[accountLanes.length];
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            if ((expectedLaneMask & (1L << laneId)) == 0) continue;
            int currentLaneId = laneId;
            AccountLaneState lane = accountLanes[laneId];
            deltas[laneId] = inLaneCommandScope(lane, ignored -> {
                if (!productLine.isDerivative()) {
                    return RuntimeSpotMatchProcessor.applyLane(takerOrderId, plan.laneEvents(currentLaneId),
                            this, instrument, baseAssetId, quoteAssetId);
                }
                RuntimeTreasuryDelta delta = new RuntimeTreasuryDelta();
                RuntimePerpetualMatchProcessor.applyLane(takerOrderId, plan.laneEvents(currentLaneId),
                        this, identities, instrument, settleAssetId, delta);
                return delta;
            });
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

    public PerpetualLaneJournal[] dispatchPerpetualSettlement(
            MatcherSettlementPlan plan, RuntimeIdentityRegistry identities) {
        assertOwner();
        if (!productLine.isDerivative() || plan == null || identities == null) {
            throw new IllegalArgumentException("invalid perpetual settlement dispatch");
        }
        OrderRuntime taker = order(plan.takerOrderId());
        if (taker == null) throw new IllegalStateException("taker order is missing");
        CoreInstrumentState instrument = instrument(identities.symbol(taker.symbolId()));
        if (instrument == null) throw new IllegalStateException("match instrument is missing");
        int settleAssetId = identities.assetId(instrument.settleAsset());
        PerpetualLaneJournal[] journals = new PerpetualLaneJournal[accountLanes.length];
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            if ((plan.requiredLaneMask() & 1L << laneId) == 0 || plan.laneEvents(laneId).isEmpty()) continue;
            PerpetualJournalBuilder builder = new PerpetualJournalBuilder(
                    plan, identities, instrument, settleAssetId, laneId);
            journals[laneId] = builder.build();
        }
        for (int laneId = 0; laneId < journals.length; laneId++) {
            PerpetualLaneJournal journal = journals[laneId];
            if (journal == null) continue;
            try {
                SettlementLaneWorker worker = settlementLaneWorkers[laneId];
                if (worker == null) {
                    worker = new SettlementLaneWorker(laneId, topology.accountLaneQueueCapacity());
                    settlementLaneWorkers[laneId] = worker;
                }
                worker.execute(journal);
            } catch (RuntimeException failure) {
                for (PerpetualLaneJournal submitted : journals) {
                    if (submitted != null && !submitted.completed()) submitted.failure = failure;
                }
                throw failure;
            }
        }
        return journals;
    }

    public RuntimeTreasuryDelta[] applyPreparedPerpetualSettlement(
            MatcherSettlementPlan plan, PerpetualLaneJournal[] journals,
            RuntimeIdentityRegistry identities) {
        assertOwner();
        if (plan == null || journals == null || journals.length != accountLanes.length || identities == null) {
            throw new IllegalArgumentException("invalid prepared perpetual settlement");
        }
        for (int laneId = 0; laneId < journals.length; laneId++) {
            PerpetualLaneJournal journal = journals[laneId];
            if (journal == null) continue;
            if (!journal.completed || journal.failure != null || journal.coreSequence != plan.coreSequence()
                    || journal.laneId != laneId) {
                throw new IllegalStateException("perpetual lane journal is incomplete", journal.failure);
            }
            AccountLaneView lane = accountLaneById(laneId);
            if (lane.revision() != journal.beforeRevision || lane.localStateHash() != journal.beforeStateHash
                    || lane.localFundsHash() != journal.beforeFundsHash) {
                throw new IllegalStateException("perpetual lane journal input is stale");
            }
            for (int index = 0; index < journal.beforeOrders.length; index++) {
                OrderRuntime current = order(journal.beforeOrders[index].orderId());
                ReservationRuntime reservation = reservation(journal.beforeReservations[index].orderId());
                if (current == null || current.revision() != journal.beforeOrders[index].revision()
                        || !journal.beforeReservations[index].equals(reservation)) {
                    throw new IllegalStateException("perpetual order journal input is stale");
                }
            }
            for (int index = 0; index < journal.accountUserIds.length; index++) {
                BalanceRuntime balance = balance(journal.accountUserIds[index], journal.settleAssetId);
                if (balance == null || balance.availableUnits() != journal.beforeAvailable[index]
                        || balance.lockedUnits() != journal.beforeLocked[index]) {
                    throw new IllegalStateException("perpetual balance journal input is stale");
                }
            }
            for (int index = 0; index < journal.positionKeys.length; index++) {
                if (!java.util.Objects.equals(position(journal.positionKeys[index]), journal.beforePositions[index])) {
                    throw new IllegalStateException("perpetual position journal input is stale");
                }
            }
        }
        RuntimeTreasuryDelta[] deltas = new RuntimeTreasuryDelta[accountLanes.length];
        for (int laneId = 0; laneId < journals.length; laneId++) {
            PerpetualLaneJournal journal = journals[laneId];
            if (journal == null) continue;
            AccountLaneState lane = accountLanes[laneId];
            inLaneCommandScope(lane, ignored -> {
                for (int index = 0; index < journal.accountUserIds.length; index++) {
                    replaceBalance(new BalanceRuntime(journal.accountUserIds[index], journal.settleAssetId,
                            journal.available[index], journal.locked[index]));
                }
                for (ReservationRuntime reservation : journal.reservations) replaceReservation(reservation);
                for (int index = 0; index < journal.positions.length; index++) {
                    replacePosition(journal.positionKeys[index], journal.positions[index]);
                }
                for (OrderRuntime order : journal.orders) replaceOrder(order);
                for (int index = 0; index < journal.accountUserIds.length; index++) {
                    for (long revision = 0; revision < journal.userRevisionIncrements[index]; revision++) {
                        advanceUserRevision(journal.accountUserIds[index]);
                    }
                }
                return null;
            });
            deltas[laneId] = journal.treasuryDelta;
        }
        OrderRuntime taker = order(plan.takerOrderId());
        CoreInstrumentState instrument = instrument(identities.symbol(taker.symbolId()));
        recordMatcherSettlementChanges(plan, identities, instrument,
                identities.assetId(instrument.baseAsset()), identities.assetId(instrument.quoteAsset()),
                identities.assetId(instrument.settleAsset()));
        return deltas;
    }

    private final class PerpetualJournalBuilder {
        private final MatcherSettlementPlan plan;
        private final RuntimeIdentityRegistry identities;
        private final CoreInstrumentState instrument;
        private final int settleAssetId;
        private final int laneId;
        private final java.util.ArrayList<OrderRuntime> orders = new java.util.ArrayList<>();
        private final java.util.ArrayList<ReservationRuntime> reservations = new java.util.ArrayList<>();
        private final java.util.ArrayList<PositionRuntime> positions = new java.util.ArrayList<>();
        private final org.eclipse.collections.impl.list.mutable.primitive.LongArrayList positionKeys =
                new org.eclipse.collections.impl.list.mutable.primitive.LongArrayList();
        private final org.eclipse.collections.impl.list.mutable.primitive.LongArrayList accountUserIds =
                new org.eclipse.collections.impl.list.mutable.primitive.LongArrayList();
        private final org.eclipse.collections.impl.list.mutable.primitive.LongArrayList available =
                new org.eclipse.collections.impl.list.mutable.primitive.LongArrayList();
        private final org.eclipse.collections.impl.list.mutable.primitive.LongArrayList locked =
                new org.eclipse.collections.impl.list.mutable.primitive.LongArrayList();
        private final org.eclipse.collections.impl.list.mutable.primitive.IntArrayList orderAccounts =
                new org.eclipse.collections.impl.list.mutable.primitive.IntArrayList();
        private final org.eclipse.collections.impl.list.mutable.primitive.IntArrayList orderPositions =
                new org.eclipse.collections.impl.list.mutable.primitive.IntArrayList();
        private final org.eclipse.collections.impl.list.mutable.primitive.LongArrayList leverages =
                new org.eclipse.collections.impl.list.mutable.primitive.LongArrayList();
        private final org.eclipse.collections.impl.list.mutable.primitive.IntArrayList operationOrders =
                new org.eclipse.collections.impl.list.mutable.primitive.IntArrayList();
        private final org.eclipse.collections.impl.list.mutable.primitive.LongArrayList operationPrices =
                new org.eclipse.collections.impl.list.mutable.primitive.LongArrayList();
        private final org.eclipse.collections.impl.list.mutable.primitive.LongArrayList operationQuantities =
                new org.eclipse.collections.impl.list.mutable.primitive.LongArrayList();
        private final org.eclipse.collections.impl.list.mutable.primitive.BooleanArrayList operationTakers =
                new org.eclipse.collections.impl.list.mutable.primitive.BooleanArrayList();
        private final org.eclipse.collections.impl.map.mutable.primitive.LongIntHashMap orderIndexes =
                new org.eclipse.collections.impl.map.mutable.primitive.LongIntHashMap();
        private final org.eclipse.collections.impl.map.mutable.primitive.LongIntHashMap positionIndexes =
                new org.eclipse.collections.impl.map.mutable.primitive.LongIntHashMap();
        private final org.eclipse.collections.impl.map.mutable.primitive.LongIntHashMap accountIndexes =
                new org.eclipse.collections.impl.map.mutable.primitive.LongIntHashMap();

        private PerpetualJournalBuilder(MatcherSettlementPlan plan, RuntimeIdentityRegistry identities,
                                        CoreInstrumentState instrument, int settleAssetId, int laneId) {
            this.plan = plan;
            this.identities = identities;
            this.instrument = instrument;
            this.settleAssetId = settleAssetId;
            this.laneId = laneId;
        }

        private PerpetualLaneJournal build() {
            OrderRuntime taker = order(plan.takerOrderId());
            for (var event : plan.laneEvents(laneId)) {
                if (topology.accountLaneId(taker.userId()) == laneId) add(taker.orderId(), event.price(), event.size(), true);
                add(event.matchedOrderId(), event.price(), event.size(), false);
            }
            int takerIndex = orderIndexes.get(taker.orderId()) - 1;
            boolean[] takers = operationTakers.toArray();
            return new PerpetualLaneJournal(plan.coreSequence(), laneId, accountLaneById(laneId),
                    orders.toArray(OrderRuntime[]::new), reservations.toArray(ReservationRuntime[]::new),
                    positions.toArray(PositionRuntime[]::new), positionKeys.toArray(), accountUserIds.toArray(),
                    available.toArray(), locked.toArray(), orderAccounts.toArray(), orderPositions.toArray(),
                    leverages.toArray(), operationOrders.toArray(), operationPrices.toArray(),
                    operationQuantities.toArray(), takers, instrument, settleAssetId, takerIndex);
        }

        private void add(long orderId, long price, long quantity, boolean taker) {
            OrderRuntime order = TradingRuntimeState.this.order(orderId);
            if (order == null || topology.accountLaneId(order.userId()) != laneId) return;
            int encodedOrderIndex = orderIndexes.get(orderId);
            int orderIndex;
            if (encodedOrderIndex == 0) {
                orderIndex = captureOrder(order);
                orderIndexes.put(orderId, orderIndex + 1);
            } else {
                orderIndex = encodedOrderIndex - 1;
            }
            operationOrders.add(orderIndex);
            operationPrices.add(price);
            operationQuantities.add(quantity);
            operationTakers.add(taker);
        }

        private int captureOrder(OrderRuntime order) {
            ReservationRuntime reservation = TradingRuntimeState.this.reservation(order.orderId());
            BalanceRuntime balance = TradingRuntimeState.this.balance(order.userId(), settleAssetId);
            if (reservation == null || balance == null) throw new IllegalStateException("fill entities are missing");
            int encodedAccountIndex = accountIndexes.get(order.userId());
            int accountIndex;
            if (encodedAccountIndex == 0) {
                accountIndex = accountUserIds.size();
                accountUserIds.add(order.userId());
                available.add(balance.availableUnits());
                locked.add(balance.lockedUnits());
                accountIndexes.put(order.userId(), accountIndex + 1);
            } else {
                accountIndex = encodedAccountIndex - 1;
            }
            String identity = order.positionSide() == CorePositionSide.NET
                    ? instrument.symbol() : instrument.symbol() + ':' + order.positionSide().name();
            long positionKey = identities.preparedPositionKey(order.userId(), identity);
            int encodedPositionIndex = positionIndexes.get(positionKey);
            int positionIndex;
            if (encodedPositionIndex == 0) {
                positionIndex = positionKeys.size();
                positionKeys.add(positionKey);
                positions.add(position(positionKey));
                positionIndexes.put(positionKey, positionIndex + 1);
            } else {
                positionIndex = encodedPositionIndex - 1;
            }
            Long leverage = TradingRuntimeState.this.leverage(
                    new CoreLeverageKey(order.userId(), instrument.symbol(), order.marginMode()));
            int index = orders.size();
            orders.add(order);
            reservations.add(reservation);
            orderAccounts.add(accountIndex);
            orderPositions.add(positionIndex);
            leverages.add(leverage == null ? instrument.maxLeveragePpm() : leverage);
            return index;
        }
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

        RuntimeTreasuryDelta[] deltas = new RuntimeTreasuryDelta[accountLanes.length];
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            if ((selectedLaneMask & (1L << laneId)) == 0) continue;
            int currentLaneId = laneId;
            AccountLaneState lane = accountLanes[laneId];
            deltas[laneId] = inLaneCommandScope(lane, ignored -> {
                RuntimeTreasuryDelta combined = new RuntimeTreasuryDelta(RuntimeTreasuryDelta.ORDER_BATCH_CAPACITY);
                for (PerpetualMatcherSettlement settlement : settlements) {
                    if ((settlement.expectedLaneMask() & (1L << currentLaneId)) == 0) continue;
                    RuntimePerpetualMatchProcessor.applyLane(
                            settlement.takerOrderId(), settlement.matchingResult().matcherEvents(), this, identities,
                            settlement.instrument(), settlement.settleAssetId(), combined);
                }
                return combined;
            });
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
                || !patchUsersBefore.isEmpty() || !patchBalancesBefore.isEmpty()
                || !patchReservationsBefore.isEmpty() || !patchOrdersBefore.isEmpty()
                || !patchLiquidationsBefore.isEmpty() || !patchRiskSnapshotsBefore.isEmpty()
                || !patchLeveragesBefore.isEmpty() || !patchAlgoOrdersBefore.isEmpty()
                || !patchTriggerOrdersBefore.isEmpty() || !patchClientOrdersBefore.isEmpty()
                || !patchTimersBefore.isEmpty() || !patchMarkPricesBefore.isEmpty()
                || !patchRiskScansBefore.isEmpty() || !patchInstrumentsBefore.isEmpty()
                || patchNextLiquidationIdChanged || patchRiskScanControlChanged;
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
        for (long orderId : patchOrdersBefore.keySet()) rollbackOrder(orderId, patchOrdersBefore.get(orderId).value());
        for (long orderId : patchReservationsBefore.keySet()) {
            PatchReservationBefore captured = patchReservationsBefore.get(orderId);
            if (captured.pending()) {
                throw new IllegalStateException("order batch overlapped an existing pending reservation");
            }
            rollbackReservation(orderId, captured.value());
        }
        for (long positionKey : changedPositions.toArray()) rollbackPosition(positionKey);
        patchBalancesBefore.forEach(this::rollbackBalance);
        patchUsersBefore.forEach(this::rollbackUser);
        for (int assetId : treasury.changedAssets().toArray()) {
            RuntimeCommitPatch.TreasuryAssetValue before = treasury.patchAssetBefore(assetId);
            treasury.setFee(assetId, before == null ? 0 : before.fee());
            treasury.setInsurance(assetId, before == null ? 0 : before.insurance(),
                    before == null ? 0 : before.deficit());
            treasury.setLiquidationFee(assetId, before == null ? 0 : before.liquidationFee());
            treasury.setFundingResidual(assetId, before == null ? 0 : before.fundingResidual());
            treasury.setRoundingResidual(assetId, before == null ? 0 : before.roundingResidual());
            treasury.setClearingPnl(assetId, before == null ? 0 : before.clearingPnl());
        }
        revision = checkpoint.revision();
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            int id = laneId;
            onLane(id, lane -> { lane.rollback(checkpoint.lanes().get(id)); return null; });
        }
        clearChangedKeys();
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
        patchInstrumentsBefore.putIfAbsent(instrument.symbol(),
                new PatchBefore<>(instruments.get(instrument.symbol())));
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
        patchLeveragesBefore.putIfAbsent(key, new PatchBefore<>(leverage(key)));
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
        patchAlgoOrdersBefore.putIfAbsent(algoOrder.algoOrderId(),
                new PatchBefore<>(algoOrder(algoOrder.algoOrderId())));
        onLane(algoOrder.userId(), lane -> lane.algoOrders.put(algoOrder.algoOrderId(), algoOrder));
        changedAlgoOrders.add(algoOrder.algoOrderId());
    }

    void removeAlgoOrder(long algoOrderId) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("algo-order state");
        patchAlgoOrdersBefore.putIfAbsent(algoOrderId, new PatchBefore<>(algoOrder(algoOrderId)));
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
        patchTimersBefore.putIfAbsent(key, new PatchBefore<>(cancelAllAfterTimers.get(key)));
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
        patchTriggerOrdersBefore.putIfAbsent(triggerOrder.triggerOrderId(),
                new PatchBefore<>(triggerOrder(triggerOrder.triggerOrderId())));
        onLane(triggerOrder.userId(), lane -> lane.triggerOrders.put(triggerOrder.triggerOrderId(), triggerOrder));
        changedTriggerOrders.add(triggerOrder.triggerOrderId());
    }

    void removeTriggerOrder(long triggerOrderId) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("trigger-order state");
        patchTriggerOrdersBefore.putIfAbsent(triggerOrderId, new PatchBefore<>(triggerOrder(triggerOrderId)));
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
        if (transfer == null || pendingTransfers.putIfAbsent(transfer.transferId(), transfer) != null) {
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
        publishedUsers.put(user.userId(), user);
        changedUsers.add(user.userId());
    }

    public void advanceUserRevision(long userId) {
        assertOwner();
        captureUserBefore(userId);
        UserRuntime current = requireUser(userId);
        UserRuntime advanced = new UserRuntime(current.productLine(), userId,
                Math.incrementExact(current.revision()), current.positionMode());
        onLane(userId, lane -> lane.users.put(userId, advanced));
        publishedUsers.put(userId, advanced);
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
        publishedUsers.remove(userId);
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
        publishedOrders.put(order.orderId(), order);
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
        publishedReservations.put(reservation.orderId(), reservation);
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
        publishedOrders.put(order.orderId(), order);
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
            publishedOrders.remove(orderId);
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
        publishedReservations.put(reservation.orderId(), reservation);
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
        publishedReservations.remove(orderId);
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
        publishedPositions.put(positionKey, position);
        positionLaneIds.put(positionKey, topology.accountLaneId(position.userId()) + 1L);
        if (laneCommandScope.get() == null) {
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
        publishedPositions.remove(positionKey);
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
        publishedOrders.put(orderId, canceled.order());
        publishedReservations.put(orderId, canceled.reservation());
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
            publishedReservations.put(orderId, released);
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
                publishedOrders.remove(terminal.orderId());
                orderLaneIds.removeKey(terminal.orderId());
                changedOrders.add(terminal.orderId());
                changedUsers.add(terminal.userId());
                if (terminal.reservationRemoved()) {
                    publishedReservations.remove(terminal.orderId());
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

    public PreparedCommit prepareCommitPatch(long projectionSequence,
                                                  long previousCoreSequence, long coreSequence,
                                                  RuntimeIdentityRegistry identities,
                                                  long previousRevision,
                                                  CoreMatcherTransition matcherTransition,
                                                  List<RuntimeCommitPatch.LaneCommit> laneCommits,
                                                  long beforeBusinessStateHash,
                                                  long businessStateHash,
                                                  long beforeFundsStateHash,
                                                  long fundsStateHash,
                                                  boolean externalAdjustment) {
        assertOwner();
        if (projectionSequence <= 0 || previousCoreSequence < 0 || coreSequence <= 0
                || identities == null || previousRevision < 0
                || matcherTransition == null) {
            throw new IllegalArgumentException("invalid runtime commit patch capture");
        }
        if (laneCommits != null && !laneCommits.isEmpty()
                && laneCommits.getFirst().coreSequence() != coreSequence) {
            throw new IllegalArgumentException("runtime commit core sequence does not match lane commit");
        }
        RuntimeCommitPatch.Builder builder = activePatchBuilder
                .sequences(previousCoreSequence, coreSequence,
                        projectionSequence - 1, projectionSequence)
                .matcherTransition(matcherTransition);
        for (long userId : changedUsers.toArray()) {
            PatchBefore<UserRuntime> captured = patchUsersBefore.get(userId);
            UserRuntime current = user(userId);
            recordUserChange(builder, userId, captured == null ? current : captured.value(), current);
        }
        patchBalancesBefore.forEach((key, before) -> {
            BalanceRuntime balance = balance(key.userId(), key.assetId());
            RuntimeCommitPatch.UserBalance after = balance == null ? null : new RuntimeCommitPatch.UserBalance(
                    balance.availableUnits(), balance.lockedUnits(), pendingReservedUnits(key.userId(), key.assetId()));
            if (!java.util.Objects.equals(before.value(), after)) {
                builder.recordBalance(topology.accountLaneId(key.userId()), key.userId(), key.assetId(),
                        before.value(), after);
            }
        });
        patchReservationsBefore.forEach((orderId, before) -> {
            ReservationRuntime after = reservation(orderId);
            ReservationRuntime beforeValue = before.value();
            long userId = after != null ? after.userId() : beforeValue == null ? 0 : beforeValue.userId();
            boolean pendingAfter = after != null && pendingReservation(orderId, after.userId());
            if (userId != 0 && (!java.util.Objects.equals(beforeValue, after) || before.pending() != pendingAfter)) {
                builder.recordReservation(topology.accountLaneId(userId), orderId,
                    beforeValue, after, before != null && before.pending(),
                    pendingAfter);
            }
        });
        patchOrdersBefore.forEach((orderId, before) -> {
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
        if (laneCommits == null || laneCommits.isEmpty()) {
            java.util.List<Integer> changedLaneIds = builder.changedLaneIds().stream().sorted().toList();
            for (int ownerGroupOffset = 0; ownerGroupOffset < changedLaneIds.size(); ownerGroupOffset++) {
                int laneId = changedLaneIds.get(ownerGroupOffset);
                AccountLaneView lane = accountLaneById(laneId);
                builder.addLaneCommit(new RuntimeCommitPatch.LaneCommit(laneId, coreSequence, coreSequence,
                        ownerGroupOffset, ownerGroupOffset + 1, lane.revision(), lane.revision(),
                        lane.localStateHash(), lane.localStateHash(), lane.localFundsHash(), lane.localFundsHash()));
            }
        }
        return new PreparedCommit(builder, identities, new RuntimeCommitPatch.SealMetadata(previousRevision,
                Math.subtractExact(revision, totalPendingReservations), beforeBusinessStateHash,
                businessStateHash, beforeFundsStateHash, fundsStateHash, builder.laneMask(), null,
                externalAdjustment));
    }

    public void abortPreparedCommit(RuntimeCommitPatch sealedPatch) {
        assertOwner();
        if (sealedPatch == null) return;
        activePatchBuilder = RuntimeCommitPatch.builder(productLine);
        for (RuntimeCommitPatch.AccountLaneOwnerGroup group : sealedPatch.accountLaneGroups()) {
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
        PatchOrderBefore captured = patchOrdersBefore.get(orderId);
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
        patchUsersBefore = clearCapturedChanges(patchUsersBefore);
        patchBalancesBefore = clearCapturedChanges(patchBalancesBefore);
        patchReservationsBefore = clearCapturedChanges(patchReservationsBefore);
        patchOrdersBefore = clearCapturedChanges(patchOrdersBefore);
        activePatchBuilder = RuntimeCommitPatch.builder(productLine);
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
        publishedOrders.put(orderId, reserved.order());
        publishedReservations.put(orderId, reserved.reservation());
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
        publishedPositions.put(positionKey, position);
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
        patchUsersBefore.putIfAbsent(userId, new PatchBefore<>(user(userId)));
    }

    private void rollbackUser(long userId, PatchBefore<UserRuntime> captured) {
        UserRuntime before = captured.value();
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
        if (before == null) publishedUsers.remove(userId); else publishedUsers.put(userId, before);
    }

    private void rollbackBalance(RuntimeCommitPatch.BalanceKey key,
                                 PatchBefore<RuntimeCommitPatch.UserBalance> captured) {
        RuntimeCommitPatch.UserBalance before = captured.value();
        onLane(key.userId(), lane -> {
            IntObjectHashMap<BalanceRuntime> balances = lane.balances.get(key.userId());
            if (before == null) {
                if (balances != null) {
                    balances.remove(key.assetId());
                    if (balances.isEmpty()) lane.balances.remove(key.userId());
                }
            } else {
                if (balances == null) {
                    balances = new IntObjectHashMap<>();
                    lane.balances.put(key.userId(), balances);
                }
                balances.put(key.assetId(), new BalanceRuntime(key.userId(), key.assetId(),
                        before.availableUnits(), before.lockedUnits()));
            }
            return null;
        });
    }

    private void rollbackOrder(long orderId, OrderRuntime before) {
        OrderRuntime current = order(orderId);
        if (current != null) onLane(current.userId(), lane -> { lane.orders.remove(orderId); return null; });
        if (before == null) {
            publishedOrders.remove(orderId);
            orderLaneIds.removeKey(orderId);
        } else {
            onLane(before.userId(), lane -> { lane.orders.put(orderId, before); return null; });
            publishedOrders.put(orderId, before);
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
            publishedReservations.remove(orderId);
            reservationLaneIds.removeKey(orderId);
        } else {
            onLane(before.userId(), lane -> {
                lane.reservations.put(orderId, before);
                addUserEntity(lane.reservationIdsByUser, before.userId(), orderId);
                return null;
            });
            publishedReservations.put(orderId, before);
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
            publishedPositions.remove(positionKey);
            positionLaneIds.removeKey(positionKey);
        } else {
            onLane(rollbackValue.userId(), lane -> {
                lane.positions.put(positionKey, rollbackValue);
                indexPosition(lane, positionKey, rollbackValue);
                return null;
            });
            publishedPositions.put(positionKey, rollbackValue);
            positionLaneIds.put(positionKey, topology.accountLaneId(rollbackValue.userId()) + 1L);
        }
    }

    private void captureBalanceBefore(long userId, int assetId) {
        RuntimeCommitPatch.BalanceKey key = new RuntimeCommitPatch.BalanceKey(userId, assetId);
        patchBalancesBefore.computeIfAbsent(key, ignored -> {
            BalanceRuntime balance = balance(userId, assetId);
            return new PatchBefore<>(balance == null ? null : new RuntimeCommitPatch.UserBalance(
                    balance.availableUnits(), balance.lockedUnits(), pendingReservedUnits(userId, assetId)));
        });
    }

    private void captureOrderBefore(long orderId) {
        patchOrdersBefore.computeIfAbsent(orderId, ignored -> {
            OrderRuntime value = order(orderId);
            return new PatchOrderBefore(value,
                    value != null && pendingReservation(orderId, value.userId()));
        });
    }

    private void captureReservationBefore(long orderId) {
        patchReservationsBefore.computeIfAbsent(orderId, ignored -> {
            ReservationRuntime reservation = reservation(orderId);
            return new PatchReservationBefore(reservation,
                    reservation != null && pendingReservation(orderId, reservation.userId()));
        });
    }

    private boolean reservationPendingBefore(long orderId) {
        PatchReservationBefore before = patchReservationsBefore.get(orderId);
        return before != null && before.pending();
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
        patchLiquidationsBefore.putIfAbsent(liquidationId, new PatchBefore<>(liquidation(liquidationId)));
    }

    private void captureRiskSnapshotBefore(long positionKey) {
        rejectUnsupportedOrderBatchMutation("risk snapshot state");
        patchRiskSnapshotsBefore.putIfAbsent(positionKey, new PatchBefore<>(riskSnapshot(positionKey)));
    }

    private void captureMarkPriceBefore(int symbolId) {
        rejectUnsupportedOrderBatchMutation("mark-price state");
        patchMarkPricesBefore.putIfAbsent(symbolId, new PatchBefore<>(markPrices.get(symbolId)));
    }

    private void captureRiskScanBefore(int symbolId) {
        rejectUnsupportedOrderBatchMutation("risk-scan state");
        patchRiskScansBefore.putIfAbsent(symbolId, new PatchBefore<>(riskScans.get(symbolId)));
    }

    private void captureClientOrderBefore(long userId, long clientKey) {
        RuntimeCommitPatch.ClientOrderKey key = new RuntimeCommitPatch.ClientOrderKey(userId, clientKey);
        patchClientOrdersBefore.putIfAbsent(key, new PatchBefore<>(orderIdByClient(userId, clientKey)));
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

    private record PatchBefore<T>(T value) {}
    private record PatchOrderBefore(OrderRuntime value, boolean pending) {}
    private record PatchReservationBefore(ReservationRuntime value, boolean pending) {}

}
