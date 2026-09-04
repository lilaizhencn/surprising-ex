package com.surprising.aeron.service.state;

import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongIntHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.eclipse.collections.impl.set.mutable.primitive.IntHashSet;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreMatcherTransition;
import com.surprising.aeron.protocol.CoreRiskScanControlView;
import com.surprising.aeron.service.matching.CoreMatchingResult;
import com.surprising.aeron.service.matching.CoreMatchingOrder;
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
    private static final int PARALLEL_SETTLEMENT_MIN_LANE_OPERATIONS = Math.max(2,
            Integer.getInteger("surprising.aeron.parallel-settlement-min-lane-operations", 2));

    private ProductLine productLine = ProductLine.LINEAR_PERPETUAL;
    private long revision;
    private final LaneTopology topology;
    private final AccountLaneState[] accountLanes;
    private final org.eclipse.collections.impl.list.mutable.primitive.LongArrayList[] laneUserScratch;
    private final SettlementLaneWorker[] laneWorkers;
    private final LaneSequenceQueue[] placeAdmissionReadyQueues;
    private final LaneSequenceQueue[] matcherSettlementReadyQueues;
    private final java.util.concurrent.atomic.AtomicLong placeAdmissionReadyLaneMask =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong matcherSettlementReadyLaneMask =
            new java.util.concurrent.atomic.AtomicLong();
    private final LaneMutationTask[] laneMutationTasks;
    private final long[] laneMutationStartedNanosScratch;
    private final Object[] laneMutationResultsScratch;
    private long laneMutationResultsMask;
    private final RuntimeTreasuryDelta aggregateTreasuryDeltaScratch =
            new RuntimeTreasuryDelta(RuntimeTreasuryDelta.ORDER_BATCH_CAPACITY);
    private final int[] accountLaneQueueHighWaterMarks;
    private final long[][] accountLaneCompletedOperations;
    private final long[][] accountLaneLatencySamples;
    private final long[][] accountLaneTotalLatencyNanos;
    private final long[][] accountLaneMaxLatencyNanos;
    private final long[] publishedLaneStateHashes;
    private final long[] publishedLaneFundsHashes;
    private final long[] publishedLaneCommittedSequences;
    private final long[] dispatchedLaneCommitSequences;
    private final java.util.ArrayDeque<LaneCommitEvent> laneCommitEventPool = new java.util.ArrayDeque<>();
    private boolean accountLanesStarted;

    private final IntObjectHashMap<MarkPriceRuntime> markPrices = new IntObjectHashMap<>();
    private final IntObjectHashMap<RiskScanRuntime> riskScans = new IntObjectHashMap<>();
    private final TreasuryRuntime treasury = new TreasuryRuntime();
    private final Map<String, CoreInstrumentState> instruments = new HashMap<>();
    private final Map<CoreCancelAllAfterKey, CoreCancelAllAfterState> cancelAllAfterTimers = new HashMap<>();
    private final Map<Long, CoreFeePolicyState> feePolicies = new HashMap<>();
    private final Map<Long, TransferRuntime> pendingTransfers = new HashMap<>();
    private final PendingReservationSequenceIndex pendingReservationsBySequence =
            new PendingReservationSequenceIndex(4_096);
    private final LongLongHashMap pendingReservationUsers = new LongLongHashMap(4_096);
    private final LongIntHashMap pendingReservationCountsByUser = new LongIntHashMap(4_096);
    private final LongLongHashMap orderLaneIds = new LongLongHashMap(4_096);
    private final LongLongHashMap reservationLaneIds = new LongLongHashMap(4_096);
    private final LongLongHashMap positionLaneIds = new LongLongHashMap(4_096);
    private final LongLongHashMap matcherSettlementRemainingScratch = new LongLongHashMap();
    private final LongObjectHashMap<UserRuntime> publishedUsers = new LongObjectHashMap<>(4_096);
    private final LongObjectHashMap<OrderRuntime> publishedOrders = new LongObjectHashMap<>(4_096);
    private final LongObjectHashMap<ReservationRuntime> publishedReservations = new LongObjectHashMap<>(4_096);
    private final LongObjectHashMap<PositionRuntime> publishedPositions = new LongObjectHashMap<>(4_096);
    private final LongObjectHashMap<LiquidationRuntime> publishedLiquidations = new LongObjectHashMap<>(4_096);
    private final LongObjectHashMap<RiskSnapshotRuntime> publishedRiskSnapshots = new LongObjectHashMap<>(4_096);
    private final PublishedLaneChanges[] publishedLaneChanges;
    private int totalPendingReservations;
    private long nextLiquidationId = 1;
    private CoreRiskScanControlView riskScanControl = CoreRiskState.defaultScanControl();
    private final LongHashSet changedUsers = new LongHashSet();
    private final LongObjectHashMap<IntHashSet> changedBalances = new LongObjectHashMap<>();
    private final PublishedLaneChanges.ChangeBuffer<OrderRuntime> changedOrders =
            new PublishedLaneChanges.ChangeBuffer<>();
    private final PublishedLaneChanges.ChangeBuffer<CoreOrderState> changedActiveOrderValues =
            new PublishedLaneChanges.ChangeBuffer<>();
    private final LongHashSet changedReservations = new LongHashSet();
    private final PublishedLaneChanges.ChangeBuffer<PositionRuntime> changedPositions =
            new PublishedLaneChanges.ChangeBuffer<>();
    private final PublishedLaneChanges.ChangeBuffer<RuntimePositionIndexValue> changedPositionIndexValues =
            new PublishedLaneChanges.ChangeBuffer<>();
    private final PublishedLaneChanges.ChangeBuffer<LiquidationRuntime> changedLiquidations =
            new PublishedLaneChanges.ChangeBuffer<>();
    private final IntHashSet changedMarkPrices = new IntHashSet();
    private final PublishedLaneChanges.ChangeBuffer<RiskSnapshotRuntime> changedRiskSnapshots =
            new PublishedLaneChanges.ChangeBuffer<>();
    private final IntHashSet changedRiskScans = new IntHashSet();
    private final HashSet<String> changedInstruments = new HashSet<>();
    private final HashSet<CoreLeverageKey> changedLeverages = new HashSet<>();
    private final LongHashSet changedAlgoOrders = new LongHashSet();
    private final HashSet<CoreCancelAllAfterKey> changedCancelAllAfterTimers = new HashSet<>();
    private final LongHashSet changedTriggerOrders = new LongHashSet();
    private final LongHashSet changedFeePolicies = new LongHashSet();
    private final LaneLongCaptures<UserRuntime>[] patchUsersBeforeByLane;
    private final LaneBalancePatches[] patchBalancesBeforeByLane;
    private final LaneLongCaptures<PatchReservationBefore>[] patchReservationsBeforeByLane;
    private final LaneLongCaptures<PatchOrderBefore>[] patchOrdersBeforeByLane;
    private final LaneLongCaptures<PositionRuntime>[] patchPositionsBeforeByLane;
    private final LaneClientOrderCaptures[] patchClientOrdersBeforeByLane;
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
    private final ThreadLocal<MatcherSettlementChanges> matcherSettlementChangesScope = new ThreadLocal<>();
    private final java.util.ArrayDeque<MatcherSettlementChanges> matcherSettlementChangesPool =
            new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<LaneCancelEvent> laneCancelEventPool = new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<LaneReplaceEvent> laneReplaceEventPool = new java.util.ArrayDeque<>();
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
        LaneLongCaptures<UserRuntime>[] userPatches =
                (LaneLongCaptures<UserRuntime>[]) new LaneLongCaptures<?>[
                        topology.accountLaneCount()];
        this.patchUsersBeforeByLane = userPatches;
        this.patchBalancesBeforeByLane = new LaneBalancePatches[topology.accountLaneCount()];
        @SuppressWarnings("unchecked")
        LaneLongCaptures<PatchReservationBefore>[] reservationPatches =
                (LaneLongCaptures<PatchReservationBefore>[]) new LaneLongCaptures<?>[topology.accountLaneCount()];
        this.patchReservationsBeforeByLane = reservationPatches;
        @SuppressWarnings("unchecked")
        LaneLongCaptures<PatchOrderBefore>[] orderPatches =
                (LaneLongCaptures<PatchOrderBefore>[]) new LaneLongCaptures<?>[topology.accountLaneCount()];
        this.patchOrdersBeforeByLane = orderPatches;
        @SuppressWarnings("unchecked")
        LaneLongCaptures<PositionRuntime>[] positionPatches =
                (LaneLongCaptures<PositionRuntime>[]) new LaneLongCaptures<?>[topology.accountLaneCount()];
        this.patchPositionsBeforeByLane = positionPatches;
        this.patchClientOrdersBeforeByLane = new LaneClientOrderCaptures[topology.accountLaneCount()];
        this.publishedLaneChanges = new PublishedLaneChanges[topology.accountLaneCount()];
        org.eclipse.collections.impl.list.mutable.primitive.LongArrayList[] routedUsers =
                new org.eclipse.collections.impl.list.mutable.primitive.LongArrayList[topology.accountLaneCount()];
        this.laneUserScratch = routedUsers;
        this.laneWorkers = new SettlementLaneWorker[topology.accountLaneCount()];
        this.placeAdmissionReadyQueues = new LaneSequenceQueue[topology.accountLaneCount()];
        this.matcherSettlementReadyQueues = new LaneSequenceQueue[topology.accountLaneCount()];
        this.laneMutationTasks = new LaneMutationTask[topology.accountLaneCount()];
        this.laneMutationStartedNanosScratch = new long[topology.accountLaneCount()];
        this.laneMutationResultsScratch = new Object[topology.accountLaneCount()];
        this.accountLaneQueueHighWaterMarks = new int[topology.accountLaneCount()];
        this.accountLaneCompletedOperations = laneMetricValues(topology.accountLaneCount());
        this.accountLaneLatencySamples = laneMetricValues(topology.accountLaneCount());
        this.accountLaneTotalLatencyNanos = laneMetricValues(topology.accountLaneCount());
        this.accountLaneMaxLatencyNanos = laneMetricValues(topology.accountLaneCount());
        this.publishedLaneStateHashes = new long[topology.accountLaneCount()];
        this.publishedLaneFundsHashes = new long[topology.accountLaneCount()];
        this.publishedLaneCommittedSequences = new long[topology.accountLaneCount()];
        this.dispatchedLaneCommitSequences = new long[topology.accountLaneCount()];
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            accountLanes[laneId] = new AccountLaneState(laneId, topology.accountLaneQueueCapacity());
            placeAdmissionReadyQueues[laneId] = new LaneSequenceQueue(topology.accountLaneQueueCapacity());
            matcherSettlementReadyQueues[laneId] = new LaneSequenceQueue(topology.accountLaneQueueCapacity());
            publishLaneHashes(accountLanes[laneId]);
            laneUserScratch[laneId] = new org.eclipse.collections.impl.list.mutable.primitive.LongArrayList(4);
            patchUsersBeforeByLane[laneId] = new LaneLongCaptures<>();
            patchBalancesBeforeByLane[laneId] = new LaneBalancePatches();
            patchReservationsBeforeByLane[laneId] = new LaneLongCaptures<>();
            patchOrdersBeforeByLane[laneId] = new LaneLongCaptures<>();
            patchPositionsBeforeByLane[laneId] = new LaneLongCaptures<>();
            patchClientOrdersBeforeByLane[laneId] = new LaneClientOrderCaptures();
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
        return publishedLaneStateHashes[laneId];
    }

    public long accountLaneLocalFundsHashById(int laneId) {
        assertOwner();
        if (laneId < 0 || laneId >= accountLanes.length) {
            throw new IllegalArgumentException("invalid laneId");
        }
        return publishedLaneFundsHashes[laneId];
    }

    public AccountLaneMetricsSnapshot accountLaneMetricsById(int laneId) {
        assertOwner();
        if (laneId < 0 || laneId >= accountLanes.length) throw new IllegalArgumentException("invalid laneId");
        int queueDepth = accountLanesStarted ? laneWorkers[laneId].depth() : 0;
        AccountLaneState.MatcherSettlementMetrics matcherMetrics =
                onLane(laneId, AccountLaneState::matcherSettlementMetrics);
        int settlementIndex = AccountLaneOperationType.SETTLEMENT.ordinal();
        long[] completed = accountLaneCompletedOperations[laneId].clone();
        long[] samples = accountLaneLatencySamples[laneId].clone();
        long[] totalLatency = accountLaneTotalLatencyNanos[laneId].clone();
        long[] maxLatency = accountLaneMaxLatencyNanos[laneId].clone();
        completed[settlementIndex] = Math.addExact(completed[settlementIndex], matcherMetrics.operations());
        samples[settlementIndex] = Math.addExact(samples[settlementIndex], matcherMetrics.operations());
        totalLatency[settlementIndex] = Math.addExact(
                totalLatency[settlementIndex], matcherMetrics.totalLatencyNanos());
        maxLatency[settlementIndex] = Math.max(
                maxLatency[settlementIndex], matcherMetrics.maxLatencyNanos());
        return new AccountLaneMetricsSnapshot(queueDepth, accountLanes[laneId].queueCapacity(),
                accountLaneQueueHighWaterMarks[laneId], 0, 0,
                completed, samples, totalLatency, maxLatency);
    }

    public void startAccountLanes() {
        assertOwner();
        if (accountLanesStarted) throw new IllegalStateException("account lanes are already started");
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            AccountLaneState lane = accountLanes[laneId];
            lane.releaseOwnerForHandoff();
            laneWorkers[laneId] = new SettlementLaneWorker(
                    "account", lane, topology.accountLaneQueueCapacity());
        }
        accountLanesStarted = true;
    }

    public void assertAccountLanesHealthy() {
        assertOwner();
        if (!accountLanesStarted) return;
        for (SettlementLaneWorker worker : laneWorkers) {
            Throwable failure = worker.failure();
            if (failure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
            if (failure instanceof Error error) throw error;
            if (failure != null) throw new IllegalStateException("account lane failed", failure);
        }
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
        if (!accountLanesStarted) return operation.apply(accountLanes[laneId]);
        LaneMutationTask task = laneMutationTasks[laneId];
        if (task == null) {
            task = new LaneMutationTask(laneId);
            laneMutationTasks[laneId] = task;
        }
        task.prepare(operation);
        accountLaneQueueHighWaterMarks[laneId] = Math.max(
                accountLaneQueueHighWaterMarks[laneId], laneWorkers[laneId].depth() + 1);
        laneWorkers[laneId].submit(task);
        @SuppressWarnings("unchecked") T result = (T) task.await();
        flushPublishedChanges(laneId);
        return result;
    }

    <T> T inLaneCommandScope(AccountLaneState lane, LaneOperation<T> operation) {
        if (operation == null) {
            throw new IllegalStateException("invalid account lane command scope");
        }
        enterLaneCommandScope(lane);
        try {
            return operation.apply(lane);
        } finally {
            exitLaneCommandScope(lane);
        }
    }

    void enterLaneCommandScope(AccountLaneState lane) {
        if (lane == null || laneCommandScope.get() != null) {
            throw new IllegalStateException("invalid account lane command scope");
        }
        lane.assertOwner();
        laneCommandScope.set(lane);
    }

    void enterMatcherSettlementScope(AccountLaneState lane, MatcherSettlementChanges changes) {
        if (changes == null || matcherSettlementChangesScope.get() != null) {
            throw new IllegalStateException("invalid matcher settlement scope");
        }
        enterLaneCommandScope(lane);
        matcherSettlementChangesScope.set(changes);
    }

    void exitLaneCommandScope(AccountLaneState lane) {
        if (lane == null || laneCommandScope.get() != lane) {
            throw new IllegalStateException("account lane command scope is not active");
        }
        laneCommandScope.remove();
        if (Thread.currentThread() == owner) flushPublishedChanges(lane.laneId());
    }

    void exitMatcherSettlementScope(AccountLaneState lane, MatcherSettlementChanges changes) {
        if (changes == null || matcherSettlementChangesScope.get() != changes) {
            throw new IllegalStateException("matcher settlement scope is not active");
        }
        matcherSettlementChangesScope.remove();
        exitLaneCommandScope(lane);
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

    public <T> T executeRiskLane(int laneId, java.util.function.Supplier<T> operation) {
        assertOwner();
        if (laneId < 0 || laneId >= accountLanes.length || operation == null) {
            throw new IllegalArgumentException("invalid risk lane command");
        }
        long startedNanos = System.nanoTime();
        T result = onLane(laneId, ignored -> operation.get());
        recordLaneOperation(laneId, AccountLaneOperationType.RISK, System.nanoTime() - startedNanos);
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
        long staleResults = laneMutationResultsMask;
        while (staleResults != 0) {
            int laneId = Long.numberOfTrailingZeros(staleResults);
            results[laneId] = null;
            staleResults &= staleResults - 1;
        }
        laneMutationResultsMask = selectedLaneMask;
        if (selectedLaneMask == 0) {
            return results;
        }
        if (workItems == 0) throw new IllegalArgumentException("account lane mutation has no work");
        int selectedCount = Long.bitCount(selectedLaneMask);
        int laneOperations = Math.max(workItems, selectedCount);
        if (!allowParallel || selectedCount < 2
                || laneOperations < PARALLEL_SETTLEMENT_MIN_LANE_OPERATIONS || !accountLanesStarted) {
            for (int laneId = 0; laneId < accountLanes.length; laneId++) {
                if ((selectedLaneMask & 1L << laneId) == 0) continue;
                int currentLaneId = laneId;
                long startedNanos = System.nanoTime();
                results[laneId] = accountLanesStarted
                        ? onLane(currentLaneId, ignored -> operation.apply(currentLaneId))
                        : inLaneCommandScope(accountLanes[currentLaneId],
                        ignored -> operation.apply(currentLaneId));
                recordLaneOperation(laneId, AccountLaneOperationType.SETTLEMENT,
                        System.nanoTime() - startedNanos);
            }
            return results;
        }
        long[] startedNanos = laneMutationStartedNanosScratch;
        long submittedLaneMask = 0;
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            if ((selectedLaneMask & 1L << laneId) == 0) continue;
            accountLaneQueueHighWaterMarks[laneId] = Math.max(
                    accountLaneQueueHighWaterMarks[laneId], laneWorkers[laneId].depth() + 1);
            startedNanos[laneId] = System.nanoTime();
            LaneMutationTask task = laneMutationTasks[laneId];
            if (task == null) {
                task = new LaneMutationTask(laneId);
                laneMutationTasks[laneId] = task;
            }
            SettlementLaneWorker worker = laneWorkers[laneId];
            task.prepareIndexed(operation);
            worker.submit(task);
            submittedLaneMask |= 1L << laneId;
        }
        completeLaneMutations(submittedLaneMask, results, startedNanos);
        return results;
    }

    private void completeLaneMutations(long laneMask, Object[] results, long[] startedNanos) {
        RuntimeException failure = null;
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            if ((laneMask & 1L << laneId) == 0) continue;
            try {
                results[laneId] = laneMutationTasks[laneId].await();
                laneWorkers[laneId].assertHealthy();
                recordLaneOperation(laneId, AccountLaneOperationType.SETTLEMENT,
                        System.nanoTime() - startedNanos[laneId]);
            } catch (RuntimeException laneFailure) {
                if (failure == null) failure = laneFailure;
                else failure.addSuppressed(laneFailure);
            }
        }
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            if ((laneMask & 1L << laneId) != 0) flushPublishedChanges(laneId);
        }
        if (failure != null) throw failure;
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

    void applyLaneUsers(AccountLaneState lane,
                        org.eclipse.collections.impl.list.mutable.primitive.LongArrayList users,
                        long coreSequence) {
        org.eclipse.collections.api.iterator.LongIterator iterator = users.longIterator();
        while (iterator.hasNext()) {
            long userId = iterator.next();
            if (!lane.owns(userId)) lane.registerUser(userId);
        }
        lane.applied(coreSequence);
        lane.committed(coreSequence);
        publishLaneHashes(lane);
    }

    void publishLaneHashes(AccountLaneState lane) {
        int laneId = lane.laneId();
        publishedLaneStateHashes[laneId] = lane.localStateHash();
        publishedLaneFundsHashes[laneId] = lane.localFundsHash();
        publishedLaneCommittedSequences[laneId] = lane.committedSequence();
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

    private static LongObjectHashMap<LongLongHashMap> copyClientOrderIndex(
            LongObjectHashMap<LongLongHashMap> index) {
        LongObjectHashMap<LongLongHashMap> copy = new LongObjectHashMap<>(index.size());
        index.forEachKeyValue((userId, values) -> copy.put(userId, new LongLongHashMap(values)));
        return copy;
    }

    private void publishUser(long userId, UserRuntime value) {
        AccountLaneState scoped = laneCommandScope.get();
        if (scoped == null) putOrRemove(publishedUsers, userId, value);
        else lanePublishedChanges(scoped.laneId()).putUser(userId, value);
    }

    private void publishOrder(long orderId, OrderRuntime value) {
        AccountLaneState scoped = laneCommandScope.get();
        if (scoped == null) putOrRemove(publishedOrders, orderId, value);
        else lanePublishedChanges(scoped.laneId()).putOrder(orderId, value);
    }

    private void publishReservation(long orderId, ReservationRuntime value) {
        AccountLaneState scoped = laneCommandScope.get();
        if (scoped == null) putOrRemove(publishedReservations, orderId, value);
        else lanePublishedChanges(scoped.laneId()).putReservation(orderId, value);
    }

    private void publishPosition(long positionKey, PositionRuntime value) {
        AccountLaneState scoped = laneCommandScope.get();
        if (scoped == null) putOrRemove(publishedPositions, positionKey, value);
        else lanePublishedChanges(scoped.laneId()).putPosition(positionKey, value);
    }

    private void publishLiquidation(long liquidationId, LiquidationRuntime value) {
        AccountLaneState scoped = laneCommandScope.get();
        if (scoped == null) putOrRemove(publishedLiquidations, liquidationId, value);
        else lanePublishedChanges(scoped.laneId()).putLiquidation(liquidationId, value);
    }

    private void publishRiskSnapshot(long positionKey, RiskSnapshotRuntime value) {
        AccountLaneState scoped = laneCommandScope.get();
        if (scoped == null) putOrRemove(publishedRiskSnapshots, positionKey, value);
        else lanePublishedChanges(scoped.laneId()).putRiskSnapshot(positionKey, value);
    }

    private PublishedLaneChanges lanePublishedChanges(int laneId) {
        MatcherSettlementChanges changes = matcherSettlementChangesScope.get();
        return changes == null ? publishedLaneChanges[laneId] : changes.publishedLaneChanges[laneId];
    }

    private void flushPublishedChanges(int laneId) {
        PublishedLaneChanges changes = publishedLaneChanges[laneId];
        changes.recordRiskChanges(this);
        changes.drainTo(
                laneId, publishedUsers, publishedOrders, publishedReservations, publishedPositions,
                publishedLiquidations, publishedRiskSnapshots,
                orderLaneIds, reservationLaneIds, positionLaneIds);
    }

    MatcherSettlementChanges acquireMatcherSettlementChanges() {
        assertOwner();
        MatcherSettlementChanges changes = matcherSettlementChangesPool.pollFirst();
        return changes == null ? new MatcherSettlementChanges(accountLanes.length) : changes;
    }

    private void releaseMatcherSettlementChanges(MatcherSettlementChanges changes) {
        changes.clear();
        matcherSettlementChangesPool.addFirst(changes);
    }

    static final class MatcherSettlementChanges {
        private final PublishedLaneChanges[] publishedLaneChanges;
        private final LaneBalancePatches[] balancePatches;
        private final RuntimeFundsAccumulator[] laneFundsDeltas;
        private final RuntimeFundsAccumulator aggregateFundsDelta = new RuntimeFundsAccumulator(32);

        private MatcherSettlementChanges(int laneCount) {
            publishedLaneChanges = new PublishedLaneChanges[laneCount];
            balancePatches = new LaneBalancePatches[laneCount];
            laneFundsDeltas = new RuntimeFundsAccumulator[laneCount];
            for (int laneId = 0; laneId < laneCount; laneId++) {
                publishedLaneChanges[laneId] = new PublishedLaneChanges();
                balancePatches[laneId] = new LaneBalancePatches();
                laneFundsDeltas[laneId] = new RuntimeFundsAccumulator();
            }
        }

        void prepareLaneTerminal(int laneId, RuntimeIdentityRegistry identities, AccountLaneState lane) {
            PublishedLaneChanges changes = publishedLaneChanges[laneId];
            changes.orders.forEach((orderId, order) -> changes.activeOrderValues.put(orderId,
                    order != null && order.status() == CoreOrderStatus.OPEN
                            ? RuntimeStateMaterializer.orderSnapshot(order, identities) : null));
            changes.positions.forEach((positionKey, position) -> changes.positionIndexValues.put(positionKey,
                    position == null ? null : RuntimePositionIndexValue.from(position, identities)));
            changes.orders.forEach((orderId, order) -> {
                if (order == null || !order.status().terminal()) return;
                ReservationRuntime reservation = lane.reservations.get(orderId);
                if (reservation != null && reservation.reservedUnits() != 0) return;
                lane.removeOrder(orderId);
                changes.removeOrderRoute(orderId);
                if (reservation != null) {
                    lane.reservations.remove(orderId);
                    removeUserEntity(lane.reservationIdsByUser, order.userId(), orderId);
                    changes.removeReservationRoute(orderId);
                }
                LongHashSet clientKeys = lane.clientKeysByOrderId.get(orderId);
                if (clientKeys != null) {
                    clientKeys.forEach(clientKey -> changes.retireClientIdentity(order.userId(), clientKey));
                }
                removeClientOrdersForOrder(lane, order.userId(), orderId);
            });
            prepareBalanceFundsDelta(balancePatches[laneId], laneFundsDeltas[laneId]);
        }

        RuntimeFundsDelta collectFundsDelta(long laneMask) {
            aggregateFundsDelta.clear();
            for (int laneId = 0; laneId < laneFundsDeltas.length; laneId++) {
                if ((laneMask & 1L << laneId) != 0) aggregateFundsDelta.add(laneFundsDeltas[laneId]);
            }
            return aggregateFundsDelta.toDelta();
        }

        void appendFundsDelta(long laneMask, RuntimeFundsAccumulator target) {
            if (target == null) throw new IllegalArgumentException("funds accumulator is required");
            for (int laneId = 0; laneId < laneFundsDeltas.length; laneId++) {
                if ((laneMask & 1L << laneId) != 0) target.add(laneFundsDeltas[laneId]);
            }
        }

        private void clear() {
            for (PublishedLaneChanges changes : publishedLaneChanges) changes.clear();
            for (LaneBalancePatches patches : balancePatches) patches.clear();
            for (RuntimeFundsAccumulator delta : laneFundsDeltas) delta.clear();
            aggregateFundsDelta.clear();
        }
    }

    private static <V> void putOrRemove(LongObjectHashMap<V> values, long key, V value) {
        if (value == null) values.remove(key); else values.put(key, value);
    }

    private static final class PublishedLaneChanges {
        private final ChangeBuffer<UserRuntime> users = new ChangeBuffer<>();
        private final ChangeBuffer<OrderRuntime> orders = new ChangeBuffer<>();
        private final ChangeBuffer<ReservationRuntime> reservations = new ChangeBuffer<>();
        private final ChangeBuffer<PositionRuntime> positions = new ChangeBuffer<>();
        private final ChangeBuffer<LiquidationRuntime> liquidations = new ChangeBuffer<>();
        private final ChangeBuffer<RiskSnapshotRuntime> riskSnapshots = new ChangeBuffer<>();
        private final ChangeBuffer<CoreOrderState> activeOrderValues = new ChangeBuffer<>();
        private final ChangeBuffer<RuntimePositionIndexValue> positionIndexValues = new ChangeBuffer<>();
        private final LongHashSet removedOrderRoutes = new LongHashSet();
        private final LongHashSet removedReservationRoutes = new LongHashSet();
        private final ClientIdentityReleaseBuffer retiredClientIdentities = new ClientIdentityReleaseBuffer();

        private void putUser(long key, UserRuntime value) {
            users.put(key, value);
        }

        private void putOrder(long key, OrderRuntime value) {
            orders.put(key, value);
        }

        private void putReservation(long key, ReservationRuntime value) {
            reservations.put(key, value);
        }

        private void putPosition(long key, PositionRuntime value) {
            positions.put(key, value);
        }

        private void putLiquidation(long key, LiquidationRuntime value) {
            liquidations.put(key, value);
        }

        private void putRiskSnapshot(long key, RiskSnapshotRuntime value) {
            riskSnapshots.put(key, value);
        }

        private void removeOrderRoute(long orderId) { removedOrderRoutes.add(orderId); }
        private void removeReservationRoute(long orderId) { removedReservationRoutes.add(orderId); }
        private void retireClientIdentity(long userId, long clientKey) {
            retiredClientIdentities.add(userId, clientKey);
        }

        private void releaseRetiredClientIdentities(RuntimeIdentityRegistry identities) {
            retiredClientIdentities.release(identities);
        }

        private void drainTo(int laneId,
                             LongObjectHashMap<UserRuntime> targetUsers,
                             LongObjectHashMap<OrderRuntime> targetOrders,
                             LongObjectHashMap<ReservationRuntime> targetReservations,
                             LongObjectHashMap<PositionRuntime> targetPositions,
                             LongObjectHashMap<LiquidationRuntime> targetLiquidations,
                             LongObjectHashMap<RiskSnapshotRuntime> targetRiskSnapshots,
                             LongLongHashMap targetOrderLanes,
                             LongLongHashMap targetReservationLanes,
                             LongLongHashMap targetPositionLanes) {
            users.drain(targetUsers, null, laneId);
            orders.drain(targetOrders, targetOrderLanes, laneId);
            reservations.drain(targetReservations, targetReservationLanes, laneId);
            positions.drain(targetPositions, targetPositionLanes, laneId);
            liquidations.drain(targetLiquidations, null, laneId);
            riskSnapshots.drain(targetRiskSnapshots, null, laneId);
            removedOrderRoutes.forEach(orderId -> {
                targetOrders.remove(orderId);
                targetOrderLanes.removeKey(orderId);
            });
            removedReservationRoutes.forEach(orderId -> {
                targetReservations.remove(orderId);
                targetReservationLanes.removeKey(orderId);
            });
            removedOrderRoutes.clear();
            removedReservationRoutes.clear();
        }

        private void recordTerminalChanges(TradingRuntimeState state,
                                           TerminalOrderSink terminalOrderSink,
                                           long coreSequence) {
            users.forEach((userId, ignored) -> state.changedUsers.add(userId));
            orders.forEach((orderId, order) -> {
                state.changedOrders.put(orderId, order);
                if (terminalOrderSink != null && order != null && order.status().terminal()) {
                    terminalOrderSink.accept(order, coreSequence);
                }
            });
            reservations.forEach((orderId, ignored) -> state.changedReservations.add(orderId));
            positions.forEach(state.changedPositions::put);
            activeOrderValues.forEach(state.changedActiveOrderValues::put);
            positionIndexValues.forEach(state.changedPositionIndexValues::put);
            recordRiskChanges(state);
        }

        private void recordRiskChanges(TradingRuntimeState state) {
            liquidations.forEach(state.changedLiquidations::put);
            riskSnapshots.forEach(state.changedRiskSnapshots::put);
        }

        private void clear() {
            users.clear();
            orders.clear();
            reservations.clear();
            positions.clear();
            liquidations.clear();
            riskSnapshots.clear();
            activeOrderValues.clear();
            positionIndexValues.clear();
            removedOrderRoutes.clear();
            removedReservationRoutes.clear();
            retiredClientIdentities.clear();
        }

        private static final class ClientIdentityReleaseBuffer {
            private long[] userIds = new long[4];
            private long[] clientKeys = new long[4];
            private int size;

            private void add(long userId, long clientKey) {
                if (userId <= 0 || clientKey <= 0) {
                    throw new IllegalArgumentException("invalid retired client identity");
                }
                if (size == userIds.length) {
                    int capacity = Math.multiplyExact(size, 2);
                    userIds = java.util.Arrays.copyOf(userIds, capacity);
                    clientKeys = java.util.Arrays.copyOf(clientKeys, capacity);
                }
                userIds[size] = userId;
                clientKeys[size] = clientKey;
                size++;
            }

            private void release(RuntimeIdentityRegistry identities) {
                if (identities == null) return;
                for (int index = 0; index < size; index++) {
                    identities.releaseClientKey(userIds[index], clientKeys[index]);
                }
                clear();
            }

            private void clear() {
                size = 0;
            }
        }

        private static final class ChangeBuffer<V> {
            private long[] keys = new long[8];
            private Object[] values = new Object[8];
            private long[] indexKeys = new long[16];
            private int[] indexSlots = new int[16];
            private int[] indexGenerations = new int[16];
            private int indexGeneration = 1;
            private int size;

            private void put(long key, V value) {
                int slot = indexOf(key);
                if (slot >= 0) {
                    values[slot] = value;
                    return;
                }
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

            private void drain(LongObjectHashMap<V> target, LongLongHashMap targetLanes, int laneId) {
                for (int index = 0; index < size; index++) {
                    long key = keys[index];
                    @SuppressWarnings("unchecked") V value = (V) values[index];
                    values[index] = null;
                    if (value == null) {
                        target.removeKey(key);
                        if (targetLanes != null) targetLanes.removeKey(key);
                    } else {
                        target.put(key, value);
                        if (targetLanes != null) targetLanes.put(key, laneId + 1L);
                    }
                }
                clear();
            }

            private boolean isEmpty() {
                return size == 0;
            }

            @SuppressWarnings("unchecked")
            private V get(long key) {
                int slot = indexOf(key);
                return slot < 0 ? null : (V) values[slot];
            }

            private boolean containsKey(long key) {
                return indexOf(key) >= 0;
            }

            private void forEach(org.eclipse.collections.api.block.procedure.primitive.LongObjectProcedure<V> consumer) {
                for (int index = 0; index < size; index++) {
                    @SuppressWarnings("unchecked") V value = (V) values[index];
                    consumer.value(keys[index], value);
                }
            }

            private org.eclipse.collections.api.iterator.LongIterator longIterator() {
                return new org.eclipse.collections.api.iterator.LongIterator() {
                    private int cursor;

                    @Override
                    public long next() {
                        if (!hasNext()) throw new java.util.NoSuchElementException();
                        return keys[cursor++];
                    }

                    @Override
                    public boolean hasNext() {
                        return cursor < size;
                    }
                };
            }

            private long[] toArray() {
                return java.util.Arrays.copyOf(keys, size);
            }

            private void clear() {
                for (int index = 0; index < size; index++) values[index] = null;
                size = 0;
                if (++indexGeneration == 0) {
                    java.util.Arrays.fill(indexGenerations, 0);
                    indexGeneration = 1;
                }
            }

            private int indexOf(long key) {
                int mask = indexSlots.length - 1;
                int position = LaneLongCaptures.longHash(key) & mask;
                while (indexGenerations[position] == indexGeneration) {
                    if (indexKeys[position] == key) return indexSlots[position];
                    position = (position + 1) & mask;
                }
                return -1;
            }

            private int emptyIndexPosition(long key) {
                int mask = indexSlots.length - 1;
                int position = LaneLongCaptures.longHash(key) & mask;
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
        }
    }

    @Override
    public void close() {
        accountLanesStarted = false;
        for (SettlementLaneWorker worker : laneWorkers) {
            if (worker != null) worker.close();
        }
    }

    private final class LaneMutationTask implements SettlementLaneWorker.Command {
        private final int laneId;
        private final LaneOperation<Object> indexedScopedOperation;
        private LaneOperation<Object> operation;
        private java.util.function.IntFunction<Object> indexedOperation;
        private Object result;
        private Throwable failure;
        private volatile boolean completed = true;
        private volatile Thread waiter;

        private LaneMutationTask(int laneId) {
            this.laneId = laneId;
            indexedScopedOperation = ignored -> indexedOperation.apply(this.laneId);
        }

        @SuppressWarnings("unchecked")
        private void prepare(LaneOperation<?> operation) {
            if (!completed) throw new IllegalStateException("account lane task is still active");
            this.operation = (LaneOperation<Object>) operation;
            indexedOperation = null;
            result = null;
            failure = null;
            completed = false;
        }

        private void prepareIndexed(java.util.function.IntFunction<Object> operation) {
            if (!completed) throw new IllegalStateException("account lane task is still active");
            this.operation = null;
            indexedOperation = operation;
            result = null;
            failure = null;
            completed = false;
        }

        @Override
        public void execute(AccountLaneState lane) {
            try {
                result = operation == null
                        ? inLaneCommandScope(lane, indexedScopedOperation)
                        : inLaneCommandScope(lane, operation);
            } catch (Throwable taskFailure) {
                failure = taskFailure;
            } finally {
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
            if (interrupted) throw new IllegalStateException("account lane mutation was interrupted");
            if (failure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
            if (failure instanceof Error error) throw error;
            if (failure != null) throw new IllegalStateException("account lane mutation failed", failure);
            return result;
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

    void recordMatcherLaneOperation(AccountLaneState lane, long latencyNanos) {
        lane.recordMatcherSettlement(latencyNanos);
    }

    void recordAdmissionLaneOperation(AccountLaneState lane, long latencyNanos) {
        recordLaneOperation(lane.laneId(), AccountLaneOperationType.COMMAND, latencyNanos);
    }

    void recordSequenceCommitLaneOperation(int laneId, long latencyNanos) {
        recordLaneOperation(laneId, AccountLaneOperationType.SETTLEMENT, latencyNanos);
    }

    private record PendingReservationCompletion(ReservationRuntime reservation) {}

    private record PendingReservationRef(long orderId, long userId) {
    }

    private record PendingReservationBatchCompletion(
            long orderId, long userId, ReservationRuntime reservation) {
        private PendingReservationBatchCompletion {
            if (orderId <= 0 || userId <= 0) {
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
            long orderId, long userId, int reservationAssetId, boolean reservationRemoved) {
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
            captureBalanceBefore(userId, reservation.assetId());
            lane.markPendingReservation(orderId, coreSequence);
            captureBalanceAfter(lane, userId, reservation.assetId());
            return null;
        });
        indexPendingReservation(userId, orderId, coreSequence, nextTotalPendingReservations);
    }

    private void indexPendingReservation(long userId, long orderId, long coreSequence,
                                         int nextTotalPendingReservations) {
        pendingReservationsBySequence.add(coreSequence, orderId);
        pendingReservationUsers.put(orderId, userId);
        pendingReservationCountsByUser.addToValue(userId, 1);
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
            LongHashSet clientKeys = accountLane.clientKeysByOrderId.get(orderId);
            if (reservation != null) captureBalanceBefore(userId, reservation.assetId());
            if (clientKeys != null) clientKeys.forEach(clientKey -> captureClientOrderBefore(userId, clientKey));
            accountLane.completePendingReservation(orderId, coreSequence);
            if (reservation != null) captureBalanceAfter(accountLane, userId, reservation.assetId());
            return new PendingReservationCompletion(reservation);
        });
        changedOrder(orderId);
        changedReservations.add(orderId);
        changedUsers.add(userId);
        if (completion.reservation() != null) changedBalance(userId, completion.reservation().assetId());
        unindexPendingReservation(orderId, coreSequence, userId, nextTotalPendingReservations);
    }

    public void completePendingReservations(long coreSequence) {
        assertOwner();
        if (coreSequence <= 0) throw new IllegalArgumentException("coreSequence must be positive");
        long[] pending = pendingReservationsBySequence.orderIds(coreSequence);
        if (pending.length == 0) return;
        List<PendingReservationRef> refs = new ArrayList<>(pending.length);
        for (long orderId : pending) {
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
                captureBalanceAfter(lane, completion.userId(), completion.reservation().assetId());
            }
            return null;
        });
        for (PendingReservationBatchCompletion completion : completions) {
            changedOrder(completion.orderId());
            changedReservations.add(completion.orderId());
            changedUsers.add(completion.userId());
            changedBalance(completion.userId(), completion.reservation().assetId());
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
                LongHashSet clientKeys = lane.clientKeysByOrderId.get(ref.orderId());
                if (reservation != null) captureBalanceBefore(ref.userId(), reservation.assetId());
                if (clientKeys != null) {
                    clientKeys.forEach(clientKey -> captureClientOrderBefore(ref.userId(), clientKey));
                }
                lane.requirePendingReservationCompletion(ref.orderId(), coreSequence);
                return new PendingReservationBatchCompletion(ref.orderId(), ref.userId(), reservation);
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
        if (indexedUserId != userId || !pendingReservationsBySequence.contains(coreSequence, orderId)) {
            throw new IllegalStateException("pending reservation index differs from account lane state");
        }
    }

    private void unindexPendingReservation(long orderId, long coreSequence, long userId,
                                           int nextTotalPendingReservations) {
        requirePendingReservationIndex(orderId, coreSequence, userId);
        pendingReservationsBySequence.remove(coreSequence, orderId);
        pendingReservationUsers.removeKey(orderId);
        int nextUserCount = Math.subtractExact(pendingReservationCountsByUser.get(userId), 1);
        if (nextUserCount == 0) pendingReservationCountsByUser.removeKey(userId);
        else pendingReservationCountsByUser.put(userId, nextUserCount);
        totalPendingReservations = nextTotalPendingReservations;
    }

    boolean pendingReservation(long orderId, long userId) {
        assertOwner();
        AccountLaneState scoped = laneCommandScope.get();
        if (scoped != null) {
            if (scoped.laneId() != topology.accountLaneId(userId)) {
                throw new IllegalStateException("pending reservation query crossed its owner lane");
            }
            return scoped.pendingReservation(orderId);
        }
        return pendingReservationUsers.getIfAbsent(orderId, 0) == userId;
    }

    long pendingReservedUnits(long userId, int assetId) {
        assertOwner();
        return onLane(userId, lane -> lane.pendingReservedUnits(userId, assetId));
    }

    int pendingReservationCount(long userId) {
        assertOwner();
        AccountLaneState scoped = laneCommandScope.get();
        if (scoped != null) {
            if (scoped.laneId() != topology.accountLaneId(userId)) {
                throw new IllegalStateException("pending reservation count crossed its owner lane");
            }
            return scoped.pendingReservationCount(userId);
        }
        return pendingReservationCountsByUser.get(userId);
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

    public long stageLaneMutation(long coreSequence, Iterable<Long> userIds) {
        assertOwner();
        if (coreSequence <= 0 || userIds == null) {
            throw new IllegalArgumentException("invalid lane apply");
        }
        clearLaneUserScratch();
        for (Long userId : userIds) {
            if (userId == null || userId <= 0) continue;
            addLaneUser(userId.longValue());
        }
        return stageLaneMutationFromScratch(coreSequence);
    }

    public long stageLaneMutation(long coreSequence, long[] userIds) {
        assertOwner();
        if (coreSequence <= 0 || userIds == null) {
            throw new IllegalArgumentException("invalid lane apply");
        }
        clearLaneUserScratch();
        for (long userId : userIds) {
            if (userId > 0) addLaneUser(userId);
        }
        return stageLaneMutationFromScratch(coreSequence);
    }

    public LaneCommitEvent dispatchLaneMutation(long coreSequence, long[] userIds) {
        assertOwner();
        if (coreSequence <= 0 || userIds == null) {
            throw new IllegalArgumentException("invalid lane apply");
        }
        clearLaneUserScratch();
        for (long userId : userIds) {
            if (userId > 0) addLaneUser(userId);
        }
        return dispatchLaneMutationFromScratch(coreSequence);
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

    private long stageLaneMutationFromScratch(long coreSequence) {
        LaneCommitEvent event = dispatchLaneMutationFromScratch(coreSequence);
        if (event == null) return 0;
        while (!event.complete()) {
            assertAccountLanesHealthy();
            Thread.onSpinWait();
        }
        long laneMask = event.requiredLaneMask();
        releaseLaneCommit(event);
        return laneMask;
    }

    private LaneCommitEvent dispatchLaneMutationFromScratch(long coreSequence) {
        long laneMask = 0;
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            org.eclipse.collections.impl.list.mutable.primitive.LongArrayList users = laneUserScratch[laneId];
            if (users.isEmpty()) continue;
            if (coreSequence <= Math.max(
                    publishedLaneCommittedSequences[laneId], dispatchedLaneCommitSequences[laneId])) {
                throw new IllegalStateException("account lane apply is out of order");
            }
            laneMask |= 1L << laneId;
        }
        if (laneMask == 0) return null;
        LaneCommitEvent event = laneCommitEventPool.pollFirst();
        if (event == null) event = new LaneCommitEvent(accountLanes.length);
        event.prepare(coreSequence, laneMask, laneUserScratch, this);
        if (!accountLanesStarted) {
            for (int laneId = 0; laneId < accountLanes.length; laneId++) {
                if ((laneMask & 1L << laneId) == 0) continue;
                event.execute(accountLanes[laneId]);
                dispatchedLaneCommitSequences[laneId] = coreSequence;
            }
            return event;
        }
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            if ((laneMask & 1L << laneId) != 0 && !laneWorkers[laneId].hasCapacity()) {
                event.discard();
                laneCommitEventPool.addFirst(event);
                throw new java.util.concurrent.RejectedExecutionException("Account Lane commit queue is full");
            }
        }
        long submittedMask = 0;
        try {
            for (int laneId = 0; laneId < accountLanes.length; laneId++) {
                long laneBit = 1L << laneId;
                if ((laneMask & laneBit) == 0) continue;
                accountLaneQueueHighWaterMarks[laneId] = Math.max(
                        accountLaneQueueHighWaterMarks[laneId], laneWorkers[laneId].depth() + 1);
                laneWorkers[laneId].submit(event);
                dispatchedLaneCommitSequences[laneId] = coreSequence;
                submittedMask |= laneBit;
            }
        } catch (RuntimeException failure) {
            if (submittedMask != 0) {
                throw new IllegalStateException("partial Account Lane commit dispatch", failure);
            }
            event.discard();
            laneCommitEventPool.addFirst(event);
            throw failure;
        }
        return event;
    }

    public boolean laneCommitComplete(LaneCommitEvent event) {
        assertOwner();
        if (event == null) throw new IllegalArgumentException("Account Lane commit event is required");
        assertAccountLanesHealthy();
        return event.complete();
    }

    public void releaseLaneCommit(LaneCommitEvent event) {
        assertOwner();
        if (event == null) throw new IllegalArgumentException("Account Lane commit event is required");
        event.clear();
        laneCommitEventPool.addFirst(event);
    }

    private RuntimeTreasuryDelta applyOrderBatchMatcherSettlement(
            long coreSequence, long expectedLaneMask, MatcherSettlementPlan plan,
            CoreMatchingResult matchingResult, RuntimeIdentityRegistry identities) {
        if (!orderBatchMutationScope) {
            throw new IllegalStateException("blocking matcher settlement is restricted to one order batch");
        }
        MatcherSettlementEvent event = dispatchMatcherSettlement(coreSequence, expectedLaneMask,
                0, -1, -1, plan, matchingResult, identities);
        while (!event.complete()) Thread.onSpinWait();
        RuntimeTreasuryDelta result = collectMatcherSettlement(event);
        releaseMatcherSettlement(event);
        return result;
    }

    public MatcherSettlementEvent dispatchMatcherSettlement(
            long coreSequence, long expectedLaneMask, long commitSequence,
            long commitTimestamp, long commitClusterPosition,
            MatcherSettlementPlan plan, CoreMatchingResult matchingResult,
            RuntimeIdentityRegistry identities) {
        assertOwner();
        long validMask = accountLanes.length == Long.SIZE ? -1L : (1L << accountLanes.length) - 1L;
        if (coreSequence <= 0 || commitSequence < 0 || expectedLaneMask == 0
                || (expectedLaneMask & ~validMask) != 0
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
        MatcherSettlementEvent event = new MatcherSettlementEvent().prepare(
                commitSequence, expectedLaneMask, commitTimestamp, commitClusterPosition,
                plan, this, identities, instrument,
                baseAssetId, quoteAssetId, settleAssetId, accountLanes.length);
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            if ((expectedLaneMask & 1L << laneId) == 0) continue;
            if (!accountLanesStarted) {
                event.execute(accountLanes[laneId]);
            } else {
                accountLaneQueueHighWaterMarks[laneId] = Math.max(
                        accountLaneQueueHighWaterMarks[laneId], laneWorkers[laneId].depth() + 1);
            laneWorkers[laneId].submit(event);
            }
        }
        return event;
    }

    public void releaseMatcherSettlement(MatcherSettlementEvent event) {
        assertOwner();
    }

    public PlaceAdmissionEvent dispatchPlaceAdmission(
            long coreSequence, long userId, ResolvedPlaceOrder order, java.util.UUID commandId,
            long openInterestSteps, RuntimeOrderAdmission.AdmissionIdentity identity,
            RuntimeIdentityRegistry.PreparedClientKey preparedClientKey, int symbolId, int assetId) {
        assertOwner();
        if (!accountLanesStarted) {
            throw new IllegalStateException("asynchronous place admission requires Account Lane workers");
        }
        int laneId = topology.accountLaneId(userId);
        PlaceAdmissionEvent event = new PlaceAdmissionEvent().prepare(
                coreSequence, userId, order, commandId, openInterestSteps, identity,
                preparedClientKey, symbolId, assetId, laneId, this);
        accountLaneQueueHighWaterMarks[laneId] = Math.max(
                accountLaneQueueHighWaterMarks[laneId], laneWorkers[laneId].depth() + 1);
        laneWorkers[laneId].submit(event);
        return event;
    }

    public void releasePlaceAdmission(PlaceAdmissionEvent event) {
        assertOwner();
    }

    void publishPlaceAdmissionReady(int laneId, long coreSequence) {
        placeAdmissionReadyQueues[laneId].publish(coreSequence);
        markLaneReady(placeAdmissionReadyLaneMask, laneId);
    }

    public long takePlaceAdmissionReadyLaneMask() {
        assertOwner();
        return placeAdmissionReadyLaneMask.getAndSet(0);
    }

    public long pollPlaceAdmissionReady(int laneId) {
        assertOwner();
        if (laneId < 0 || laneId >= placeAdmissionReadyQueues.length) {
            throw new IllegalArgumentException("invalid Account Lane id");
        }
        return placeAdmissionReadyQueues[laneId].poll();
    }

    void publishMatcherSettlementReady(int laneId, long coreSequence) {
        matcherSettlementReadyQueues[laneId].publish(coreSequence);
        markLaneReady(matcherSettlementReadyLaneMask, laneId);
    }

    public long takeMatcherSettlementReadyLaneMask() {
        assertOwner();
        return matcherSettlementReadyLaneMask.getAndSet(0);
    }

    public long pollMatcherSettlementReady(int laneId) {
        assertOwner();
        if (laneId < 0 || laneId >= matcherSettlementReadyQueues.length) {
            throw new IllegalArgumentException("invalid Account Lane id");
        }
        return matcherSettlementReadyQueues[laneId].poll();
    }

    private static void markLaneReady(java.util.concurrent.atomic.AtomicLong readyMask, int laneId) {
        long laneBit = 1L << laneId;
        long current;
        do {
            current = readyMask.getPlain();
        } while (!readyMask.weakCompareAndSetRelease(current, current | laneBit));
    }

    public CoreMatchingOrder collectPlaceAdmission(PlaceAdmissionEvent event) {
        assertOwner();
        if (event == null || !event.complete()) return null;
        if (event.rejection() != null) return null;
        long orderId = event.orderId();
        long userId = event.userId();
        if (pendingReservationUsers.containsKey(orderId)) {
            throw new IllegalStateException("place admission was collected twice");
        }
        int laneId = topology.accountLaneId(userId);
        publishedUsers.put(userId, event.admittedUser());
        publishedOrders.put(orderId, event.admittedOrder());
        publishedReservations.put(orderId, event.admittedReservation());
        orderLaneIds.put(orderId, laneId + 1L);
        reservationLaneIds.put(orderId, laneId + 1L);
        indexPendingReservation(userId, orderId, event.coreSequence(),
                Math.incrementExact(totalPendingReservations));
        revision = Math.incrementExact(revision);
        return event.matchingOrder();
    }

    public RuntimeTreasuryDelta collectMatcherSettlement(MatcherSettlementEvent event) {
        return collectMatcherSettlement(event, null, null);
    }

    public RuntimeTreasuryDelta collectMatcherSettlement(
            MatcherSettlementEvent event,
            RuntimeFundsAccumulator fundsAccumulator,
            TerminalOrderSink terminalOrderSink) {
        assertOwner();
        if (event == null || !event.complete()) return null;
        RuntimeTreasuryDelta aggregate = event.collectTreasuryDelta();
        unindexMatcherPendingReservations(event.plan());
        long laneMask = event.requiredLaneMask();
        MatcherSettlementChanges changes = event.commitSequence() == 0 ? null : event.takeChanges();
        try {
            for (int laneId = 0; laneId < accountLanes.length; laneId++) {
                if ((laneMask & 1L << laneId) != 0) {
                    if (event.commitSequence() == 0) flushPublishedChanges(laneId);
                    else {
                        changes.publishedLaneChanges[laneId].recordTerminalChanges(
                                this, terminalOrderSink, event.plan().coreSequence());
                        changes.publishedLaneChanges[laneId].drainTo(
                                laneId, publishedUsers, publishedOrders, publishedReservations, publishedPositions,
                                publishedLiquidations, publishedRiskSnapshots,
                                orderLaneIds, reservationLaneIds, positionLaneIds);
                        changes.publishedLaneChanges[laneId].releaseRetiredClientIdentities(event.identities());
                        LaneBalancePatches balances = changes.balancePatches[laneId];
                        for (int index = 0; index < balances.size(); index++) {
                            changedUsers.add(balances.userId(index));
                            changedBalance(balances.userId(index), balances.assetId(index));
                        }
                    }
                }
            }
            if (terminalOrderSink != null) terminalOrderSink.completeSequence();
            if (event.commitSequence() != 0) {
                if (fundsAccumulator == null) event.collectedFundsDelta(changes.collectFundsDelta(laneMask));
                else changes.appendFundsDelta(laneMask, fundsAccumulator);
            }
            return aggregate;
        } finally {
            if (changes != null) releaseMatcherSettlementChanges(changes);
        }
    }

    public LaneCancelEvent dispatchCancel(
            long coreSequence, long userId, long orderId, long commitTimestamp, long commitClusterPosition) {
        return dispatchCancel(coreSequence, userId, orderId, commitTimestamp, commitClusterPosition, null);
    }

    public LaneCancelEvent dispatchCancel(
            long coreSequence, long userId, long orderId, long commitTimestamp, long commitClusterPosition,
            RuntimeIdentityRegistry identities) {
        assertOwner();
        if (!accountLanesStarted) throw new IllegalStateException("asynchronous cancel requires Account Lanes");
        int laneId = topology.accountLaneId(userId);
        MatcherSettlementChanges changes = acquireMatcherSettlementChanges();
        LaneCancelEvent event = laneCancelEventPool.pollFirst();
        if (event == null) event = new LaneCancelEvent();
        event.prepare(coreSequence, userId, orderId, commitTimestamp, commitClusterPosition,
                laneId, this, identities, changes);
        accountLaneQueueHighWaterMarks[laneId] = Math.max(
                accountLaneQueueHighWaterMarks[laneId], laneWorkers[laneId].depth() + 1);
        try {
            laneWorkers[laneId].submit(event);
        } catch (RuntimeException | Error failure) {
            event.discard();
            releaseMatcherSettlementChanges(changes);
            laneCancelEventPool.addFirst(event);
            throw failure;
        }
        return event;
    }

    public void collectCancel(LaneCancelEvent event, RuntimeFundsAccumulator fundsAccumulator,
                              TerminalOrderSink terminalOrderSink) {
        assertOwner();
        if (event == null || !event.complete()) throw new IllegalStateException("cancel event is incomplete");
        int laneId = event.laneId();
        MatcherSettlementChanges changes = event.takeChanges();
        try {
            changes.publishedLaneChanges[laneId].recordTerminalChanges(
                    this, terminalOrderSink, event.coreSequence());
            changes.publishedLaneChanges[laneId].drainTo(
                    laneId, publishedUsers, publishedOrders, publishedReservations, publishedPositions,
                    publishedLiquidations, publishedRiskSnapshots,
                    orderLaneIds, reservationLaneIds, positionLaneIds);
            changes.publishedLaneChanges[laneId].releaseRetiredClientIdentities(event.identities());
            LaneBalancePatches balances = changes.balancePatches[laneId];
            for (int index = 0; index < balances.size(); index++) {
                changedUsers.add(balances.userId(index));
                changedBalance(balances.userId(index), balances.assetId(index));
            }
            if (terminalOrderSink != null) terminalOrderSink.completeSequence();
            if (fundsAccumulator != null) changes.appendFundsDelta(event.requiredLaneMask(), fundsAccumulator);
        } finally {
            releaseMatcherSettlementChanges(changes);
        }
    }

    public void releaseCancel(LaneCancelEvent event) {
        assertOwner();
        if (event == null) throw new IllegalArgumentException("cancel event is required");
        event.clear();
        laneCancelEventPool.addFirst(event);
    }

    public LaneReplaceEvent dispatchReplace(
            long coreSequence, long userId, long originalOrderId, long[] preCancelOrderIds,
            ResolvedPlaceOrder replacement,
            java.util.UUID commandId, long requiredReservation, long clientKey, int symbolId, int assetId,
            long commitTimestamp, long commitClusterPosition, RuntimeIdentityRegistry identities) {
        assertOwner();
        if (!accountLanesStarted) throw new IllegalStateException("asynchronous replace requires Account Lanes");
        int laneId = topology.accountLaneId(userId);
        MatcherSettlementChanges changes = acquireMatcherSettlementChanges();
        LaneReplaceEvent event = laneReplaceEventPool.pollFirst();
        if (event == null) event = new LaneReplaceEvent();
        event.prepare(coreSequence, userId, originalOrderId, preCancelOrderIds, replacement, commandId,
                requiredReservation, clientKey, symbolId, assetId, commitTimestamp, commitClusterPosition,
                laneId, this, identities, changes);
        accountLaneQueueHighWaterMarks[laneId] = Math.max(
                accountLaneQueueHighWaterMarks[laneId], laneWorkers[laneId].depth() + 1);
        laneWorkers[laneId].submit(event);
        return event;
    }

    public void collectReplace(LaneReplaceEvent event, RuntimeFundsAccumulator fundsAccumulator,
                               TerminalOrderSink terminalOrderSink) {
        assertOwner();
        if (event == null || !event.complete()) throw new IllegalStateException("replace event is incomplete");
        int laneId = event.laneId();
        MatcherSettlementChanges changes = event.takeChanges();
        try {
            changes.publishedLaneChanges[laneId].recordTerminalChanges(
                    this, terminalOrderSink, event.coreSequence());
            changes.publishedLaneChanges[laneId].drainTo(
                    laneId, publishedUsers, publishedOrders, publishedReservations, publishedPositions,
                    publishedLiquidations, publishedRiskSnapshots,
                    orderLaneIds, reservationLaneIds, positionLaneIds);
            changes.publishedLaneChanges[laneId].releaseRetiredClientIdentities(event.identities());
            LaneBalancePatches balances = changes.balancePatches[laneId];
            for (int index = 0; index < balances.size(); index++) {
                changedUsers.add(balances.userId(index));
                changedBalance(balances.userId(index), balances.assetId(index));
            }
            indexPendingReservation(event.userId(), event.replacementOrderId(), event.coreSequence(),
                    Math.incrementExact(totalPendingReservations));
            if (fundsAccumulator != null) changes.appendFundsDelta(event.requiredLaneMask(), fundsAccumulator);
        } finally {
            releaseMatcherSettlementChanges(changes);
        }
    }

    public void releaseReplace(LaneReplaceEvent event) {
        assertOwner();
        if (event == null) throw new IllegalArgumentException("replace event is required");
        event.clear();
        laneReplaceEventPool.addFirst(event);
    }

    public OrderRuntime changedOrderValue(long orderId) {
        assertOwner();
        return changedOrders.get(orderId);
    }

    @FunctionalInterface
    public interface TerminalOrderSink {
        void accept(OrderRuntime order, long coreSequence);

        default void completeSequence() {
        }
    }

    LongLongHashMap matcherSettlementRemainingScratch() {
        assertOwner();
        return matcherSettlementRemainingScratch;
    }

    void completeMatcherPendingReservations(AccountLaneState lane, MatcherSettlementPlan plan) {
        for (int index = 0; index < plan.orderCount(); index++) {
            long orderId = plan.orderId(index);
            if (lane.pendingReservationSequences.getIfAbsent(orderId, 0) != plan.coreSequence()) continue;
            ReservationRuntime reservation = lane.reservations.get(orderId);
            if (reservation == null || topology.accountLaneId(reservation.userId()) != lane.laneId()) {
                throw new IllegalStateException("matcher pending reservation is missing from its owner lane");
            }
            captureUserBefore(reservation.userId());
            captureOrderBefore(orderId);
            captureReservationBefore(orderId);
            captureBalanceBefore(reservation.userId(), reservation.assetId());
            LongHashSet clientKeys = lane.clientKeysByOrderId.get(orderId);
            if (clientKeys != null) {
                clientKeys.forEach(clientKey -> captureClientOrderBefore(reservation.userId(), clientKey));
            }
            lane.completePendingReservation(orderId, plan.coreSequence());
            captureBalanceAfter(lane, reservation.userId(), reservation.assetId());
        }
    }

    void stampMatcherOrders(AccountLaneState lane, MatcherSettlementPlan plan,
                            long timestamp, long clusterPosition) {
        for (int index = 0; index < plan.orderCount(); index++) {
            OrderRuntime order = lane.orders.get(plan.orderId(index));
            if (order == null || order.updatedAtEpochMillis() == timestamp
                    && order.clusterPosition() == clusterPosition) {
                continue;
            }
            replaceOrder(order.withCommitMetadata(timestamp, clusterPosition));
        }
    }

    void stampOrderInLane(AccountLaneState lane, long orderId, long timestamp, long clusterPosition) {
        if (lane == null || laneCommandScope.get() != lane || matcherSettlementChangesScope.get() == null) {
            throw new IllegalStateException("order stamp must execute in its owning Account Lane");
        }
        OrderRuntime order = lane.orders.get(orderId);
        if (order != null && (order.updatedAtEpochMillis() != timestamp
                || order.clusterPosition() != clusterPosition)) {
            replaceOrder(order.withCommitMetadata(timestamp, clusterPosition));
        }
    }

    private void unindexMatcherPendingReservations(MatcherSettlementPlan plan) {
        if (!pendingReservationsBySequence.containsKey(plan.coreSequence())) return;
        for (int index = 0; index < plan.orderCount(); index++) {
            long orderId = plan.orderId(index);
            if (!pendingReservationsBySequence.contains(plan.coreSequence(), orderId)) continue;
            long userId = pendingReservationUsers.getIfAbsent(orderId, 0);
            if (userId == 0) throw new IllegalStateException("matcher pending reservation owner is missing");
            unindexPendingReservation(orderId, plan.coreSequence(), userId,
                    Math.subtractExact(totalPendingReservations, 1));
        }
    }

    public RuntimeTreasuryDelta applyOrderBatchMatcherSettlement(
            long coreSequence, long expectedLaneMask, long takerOrderId,
            CoreMatchingResult matchingResult, RuntimeIdentityRegistry identities) {
        OrderRuntime taker = order(takerOrderId);
        if (taker == null) throw new IllegalStateException("taker order is missing");
        MatcherSettlementPlan plan = MatcherSettlementPlan.build(coreSequence, takerOrderId, taker.userId(),
                new long[]{takerOrderId}, matchingResult, this, identities);
        if (plan.requiredLaneMask() != expectedLaneMask) {
            throw new IllegalStateException("matcher settlement lane mask mismatch");
        }
        return applyOrderBatchMatcherSettlement(
                coreSequence, expectedLaneMask, plan, matchingResult, identities);
    }

    public RuntimeTreasuryDelta applyNoTradeMatcherSettlements(
            long coreSequence, long userId, java.util.List<Long> takerOrderIds,
            java.util.List<CoreMatchingResult> matchingResults, RuntimeIdentityRegistry identities) {
        assertOwner();
        if (coreSequence <= 0 || userId <= 0 || takerOrderIds == null || matchingResults == null
                || takerOrderIds.isEmpty() || takerOrderIds.size() != matchingResults.size()
                || identities == null) {
            throw new IllegalArgumentException("invalid no-trade matcher settlement batch");
        }
        MatcherSettlementPlan[] plans = new MatcherSettlementPlan[takerOrderIds.size()];
        long expectedLaneMask = topology.accountLaneMask(userId);
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
            if (productLine.isDerivative()) {
                RuntimePerpetualMatchProcessor.validateAndPrepare(
                        takerOrderId, matchingResult.matcherEvents(), this, identities);
            } else {
                RuntimeSpotMatchProcessor.validate(takerOrderId, matchingResult.matcherEvents(), this);
            }
            MatcherSettlementPlan plan = MatcherSettlementPlan.build(coreSequence, takerOrderId, userId,
                    new long[]{takerOrderId}, matchingResult, this, identities);
            if (plan.requiredLaneMask() != expectedLaneMask) {
                throw new IllegalStateException("no-trade matcher settlement lane mask mismatch");
            }
            plans[index] = plan;
        }
        MatcherSettlementEvent[] events = new MatcherSettlementEvent[plans.length];
        for (int index = 0; index < plans.length; index++) {
            events[index] = dispatchMatcherSettlement(coreSequence, expectedLaneMask, 0,
                    -1, -1, plans[index], matchingResults.get(index), identities);
        }
        awaitMatcherSettlementBatch(events);
        aggregateTreasuryDeltaScratch.clear();
        for (MatcherSettlementEvent event : events) {
            aggregateTreasuryDeltaScratch.merge(collectMatcherSettlement(event));
            releaseMatcherSettlement(event);
        }
        return aggregateTreasuryDeltaScratch;
    }

    public RuntimeTreasuryDelta applyPerpetualMatcherSettlements(
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
            MatcherSettlementPlan plan = MatcherSettlementPlan.build(coreSequence, takerOrderId, taker.userId(),
                    new long[]{takerOrderId}, matchingResult, this, identities);
            if (plan.requiredLaneMask() != expectedLaneMask) {
                throw new IllegalStateException("perpetual matcher settlement lane mask mismatch");
            }
            settlements.add(new PerpetualMatcherSettlement(expectedLaneMask, matchingResult, plan));
        }
        RuntimePerpetualMatchProcessor.validateAndPrepareBatch(
                takerOrderIds, matchingResults, this, identities, perpetualBatchValidationScratch);

        MatcherSettlementEvent[] events = new MatcherSettlementEvent[settlements.size()];
        for (int index = 0; index < settlements.size(); index++) {
            PerpetualMatcherSettlement settlement = settlements.get(index);
            events[index] = dispatchMatcherSettlement(coreSequence, settlement.expectedLaneMask(), 0,
                    -1, -1, settlement.plan(), settlement.matchingResult(), identities);
        }
        awaitMatcherSettlementBatch(events);
        aggregateTreasuryDeltaScratch.clear();
        for (MatcherSettlementEvent event : events) {
            aggregateTreasuryDeltaScratch.merge(collectMatcherSettlement(event));
            releaseMatcherSettlement(event);
        }
        return aggregateTreasuryDeltaScratch;
    }

    private static void awaitMatcherSettlementBatch(MatcherSettlementEvent[] events) {
        for (MatcherSettlementEvent event : events) {
            while (!event.complete()) Thread.onSpinWait();
        }
    }

    private record PerpetualMatcherSettlement(
            long expectedLaneMask,
            CoreMatchingResult matchingResult,
            MatcherSettlementPlan plan) {
    }

    void recordUserSettlementChanges(long userId, int assetId, long positionKey) {
        assertOwner();
        if (userId <= 0 || assetId < 0 || positionKey <= 0) {
            throw new IllegalArgumentException("invalid user settlement changes");
        }
        changedUsers.add(userId);
        changedBalance(userId, assetId);
        changedPosition(positionKey);
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
        for (AccountLaneState lane : accountLanes) {
            lane.rebuildLocalHashes();
            publishLaneHashes(lane);
        }
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
                || !changedInstruments.isEmpty() || !changedLeverages.isEmpty()
                || !changedAlgoOrders.isEmpty() || !changedCancelAllAfterTimers.isEmpty()
                || !changedTriggerOrders.isEmpty() || !changedFeePolicies.isEmpty()
                || hasCapturedUsers() || hasCapturedBalances()
                || hasCaptured(patchReservationsBeforeByLane) || hasCaptured(patchOrdersBeforeByLane)
                || !patchLiquidationsBefore.isEmpty() || !patchRiskSnapshotsBefore.isEmpty()
                || !patchLeveragesBefore.isEmpty() || !patchAlgoOrdersBefore.isEmpty()
                || !patchTriggerOrdersBefore.isEmpty() || hasCapturedClientOrders()
                || !patchTimersBefore.isEmpty() || !patchMarkPricesBefore.isEmpty()
                || !patchRiskScansBefore.isEmpty() || !patchInstrumentsBefore.isEmpty()
                || !patchPendingTransfersBefore.isEmpty() || !patchFeePoliciesBefore.isEmpty()
                || patchNextLiquidationIdChanged || patchRiskScanControlChanged;
    }

    private boolean hasCapturedUsers() {
        for (LaneLongCaptures<UserRuntime> captured : patchUsersBeforeByLane) {
            if (!captured.isEmpty()) return true;
        }
        return false;
    }

    private static boolean hasCaptured(LaneLongCaptures<?>[] capturedByLane) {
        for (LaneLongCaptures<?> captured : capturedByLane) if (!captured.isEmpty()) return true;
        return false;
    }

    private boolean hasCapturedBalances() {
        for (LaneBalancePatches captured : patchBalancesBeforeByLane) {
            if (captured.size() != 0) return true;
        }
        return false;
    }

    private boolean hasCapturedClientOrders() {
        for (LaneClientOrderCaptures captured : patchClientOrdersBeforeByLane) {
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
                publishLaneHashes(lane);
                return null;
            });
        }
        pendingReservationsBySequence.clear();
        pendingReservationUsers.clear();
        pendingReservationCountsByUser.clear();
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

    public long commandRevisionCheckpoint() {
        assertOwner();
        return revision;
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

    public void rollbackActiveCommand(long revisionCheckpoint, long coreSequence) {
        assertOwner();
        if (revisionCheckpoint < 0 || coreSequence <= 0) {
            throw new IllegalArgumentException("invalid runtime command rollback checkpoint");
        }
        if (pendingReservationsBySequence.containsKey(coreSequence)) {
            completePendingReservations(coreSequence);
        }
        for (LaneClientOrderCaptures captured : patchClientOrdersBeforeByLane) {
            for (int index = 0; index < captured.size(); index++) {
                long userId = captured.userId(index);
                long clientKey = captured.clientKey(index);
                Long beforeOrderId = captured.beforeOrderId(index);
                onLane(userId, lane -> {
                    removeClientOrderIndex(lane, userId, clientKey);
                    if (beforeOrderId != null) {
                        putClientOrderIndex(lane, userId, clientKey, beforeOrderId);
                    }
                    return null;
                });
            }
        }
        for (LaneLongCaptures<PatchOrderBefore> capturedOrders : patchOrdersBeforeByLane) {
            for (int index = 0; index < capturedOrders.size(); index++) {
                rollbackOrder(capturedOrders.key(index), capturedOrders.value(index).value());
            }
        }
        for (LaneLongCaptures<PatchReservationBefore> capturedReservations : patchReservationsBeforeByLane) {
            for (int index = 0; index < capturedReservations.size(); index++) {
                long orderId = capturedReservations.key(index);
                PatchReservationBefore captured = capturedReservations.value(index);
                if (captured.pending()) {
                    throw new IllegalStateException("order batch overlapped an existing pending reservation");
                }
                rollbackReservation(orderId, captured.value());
            }
        }
        for (long positionKey : changedPositions.toArray()) rollbackPosition(positionKey);
        for (LaneBalancePatches capturedBalances : patchBalancesBeforeByLane) {
            for (int index = 0; index < capturedBalances.size(); index++) {
                rollbackBalance(capturedBalances.userId(index), capturedBalances.assetId(index),
                        capturedBalances.before(index));
            }
        }
        for (LaneLongCaptures<UserRuntime> capturedUsers : patchUsersBeforeByLane) {
            for (int index = 0; index < capturedUsers.size(); index++) {
                rollbackUser(capturedUsers.key(index), capturedUsers.value(index));
            }
        }
        rollbackCommandGlobals();
        treasury.rollbackChangedValues();
        revision = revisionCheckpoint;
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
            putOrRemove(publishedLiquidations, id, restored);
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
            putOrRemove(publishedRiskSnapshots, key, restored);
        });
        patchLeveragesBefore.forEach((key, before) -> onLane(key.userId(), lane -> {
            if (before.value() == null) {
                lane.leverages.remove(key);
                Set<CoreLeverageKey> keys = lane.leverageKeysByUser.get(key.userId());
                if (keys != null) {
                    keys.remove(key);
                    if (keys.isEmpty()) lane.leverageKeysByUser.remove(key.userId());
                }
            } else {
                lane.leverages.put(key, before.value());
                lane.leverageKeysByUser.getIfAbsentPut(key.userId(), HashSet::new).add(key);
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
        AccountLaneState scoped = laneCommandScope.get();
        if (scoped != null) {
            if (scoped.laneId() != topology.accountLaneId(userId)) {
                throw new IllegalStateException("balance query crossed its owner lane");
            }
            IntObjectHashMap<BalanceRuntime> balances = scoped.balances.get(userId);
            return balances == null ? null : balances.get(assetId);
        }
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
            Set<CoreLeverageKey> keys = lane.leverageKeysByUser.get(userId);
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
            LongObjectHashMap<LongHashSet> byUser = lane.positionKeysBySymbolAndUser.get(symbolId);
            LongHashSet keys = byUser == null ? null : byUser.get(userId);
            return keys == null ? Collections.emptyNavigableSet()
                    : Collections.unmodifiableNavigableSet(toSortedSet(keys));
        });
    }

    public LiquidationRuntime liquidation(long liquidationId) {
        assertOwner();
        AccountLaneState scoped = laneCommandScope.get();
        if (scoped != null) return scoped.liquidations.get(liquidationId);
        return publishedLiquidations.get(liquidationId);
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
        return publishedRiskSnapshots.get(positionKey);
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
        int accountLane = Integer.compare(left.accountLaneId(), right.accountLaneId());
        if (accountLane != 0) return accountLane;
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
            HashSet<CoreLeverageKey> userKeys = lane.leverageKeysByUser.get(key.userId());
            if (userKeys == null) {
                userKeys = new HashSet<>();
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
            onLane(laneId, lane -> {
                lane.algoOrders.forEachKeyValue(values::put);
                return null;
            });
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
        if (scoped != null) {
            TreeMap<Long, CoreTriggerOrderState> values = new TreeMap<>();
            scoped.triggerOrders.forEachKeyValue(values::put);
            return values;
        }
        TreeMap<Long, CoreTriggerOrderState> values = new TreeMap<>();
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            onLane(laneId, lane -> {
                lane.triggerOrders.forEachKeyValue(values::put);
                return null;
            });
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
        CoreFeePolicyState selected = null;
        for (CoreFeePolicyState policy : feePolicies.values()) {
            if (policy.effective(userId, normalizedSymbol, clusterTimestamp)
                    && (selected == null || policy.compareTo(selected) < 0)) {
                selected = policy;
            }
        }
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
            LongLongHashMap userClientOrders = lane.clientOrderIndex.get(userId);
            return userClientOrders == null || !userClientOrders.containsKey(clientKey)
                    ? null : userClientOrders.get(clientKey);
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
        onLane(userId, lane -> {
            LongLongHashMap userClientOrders = lane.clientOrderIndex.get(userId);
            if (userClientOrders != null) userClientOrders.forEachKeyValue((clientKey, orderId) -> {
                removeClientOrderReverse(lane, orderId, clientKey);
            });
            lane.users.remove(userId);
            lane.removeUser(userId);
            lane.balances.remove(userId);
            lane.clientOrderIndex.remove(userId);
            lane.reservationIdsByUser.remove(userId);
            lane.positionKeysByUser.remove(userId);
            lane.leverageKeysByUser.remove(userId);
            return null;
        });
        publishUser(userId, null);
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
            captureBalanceAfter(lane, userId, assetId);
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
        onLane(order.userId(), lane -> { lane.putOrder(order); return null; });
        publishOrder(order.orderId(), order);
        orderLaneIds.put(order.orderId(), topology.accountLaneId(order.userId()) + 1L);
        changedOrder(order.orderId(), order);
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
        onLane(order.userId(), lane -> { lane.putOrder(order); return null; });
        publishOrder(order.orderId(), order);
        if (previous == null) {
            if (matcherSettlementChangesScope.get() == null) {
                orderLaneIds.put(order.orderId(), topology.accountLaneId(order.userId()) + 1L);
            }
        }
        if (matcherSettlementChangesScope.get() == null) {
            changedOrder(order.orderId(), order);
            changedUsers.add(order.userId());
        }
    }

    public void removeOrder(long orderId) {
        assertOwner();
        captureOrderBefore(orderId);
        OrderRuntime previous = order(orderId);
        if (previous != null) {
            captureUserBefore(previous.userId());
            onLane(previous.userId(), lane -> { lane.removeOrder(orderId); return null; });
            publishOrder(orderId, null);
            orderLaneIds.removeKey(orderId);
            changedOrder(orderId, null);
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
                boolean pending = lane.pendingReservation(previous.orderId());
                if (pending) {
                    captureBalanceBefore(previous.userId(), previous.assetId());
                    captureBalanceBefore(reservation.userId(), reservation.assetId());
                }
                lane.replacePendingReservation(previous, reservation);
                lane.reservations.remove(previous.orderId());
                removeUserEntity(lane.reservationIdsByUser, previous.userId(), previous.orderId());
                if (pending) {
                    captureBalanceAfter(lane, previous.userId(), previous.assetId());
                    captureBalanceAfter(lane, reservation.userId(), reservation.assetId());
                }
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
            if (matcherSettlementChangesScope.get() == null) {
                reservationLaneIds.put(reservation.orderId(), topology.accountLaneId(reservation.userId()) + 1L);
            }
        }
        if (matcherSettlementChangesScope.get() == null) {
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
            captureBalanceAfter(lane, userId, assetId);
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
            captureBalanceAfter(lane, userId, assetId);
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
            changedPosition(positionKey, position);
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
            publishLiquidation(liquidation.liquidationId(), liquidation);
            return null;
        });
        if (laneCommandScope.get() == null) {
            changedLiquidations.put(liquidation.liquidationId(), liquidation);
        }
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
        onLane(snapshot.userId(), lane -> {
            lane.riskSnapshots.put(positionKey, snapshot);
            publishRiskSnapshot(positionKey, snapshot);
            return null;
        });
        if (laneCommandScope.get() == null) changedRiskSnapshots.put(positionKey, snapshot);
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
                publishLiquidation(previous.liquidationId(), null);
                return null;
            });
        }
        onLane(liquidation.userId(), lane -> {
            if (previous.userId() == liquidation.userId()) removeActiveLiquidation(lane, previous);
            lane.liquidations.put(liquidation.liquidationId(), liquidation);
            indexActiveLiquidation(lane, liquidation);
            publishLiquidation(liquidation.liquidationId(), liquidation);
            return null;
        });
        if (laneCommandScope.get() == null) {
            changedLiquidations.put(liquidation.liquidationId(), liquidation);
        }
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
        changedPosition(positionKey, null);
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
                publishLiquidation(liquidationId, null);
                return null;
            });
            if (laneCommandScope.get() == null) changedLiquidations.put(liquidationId, null);
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
        if (previous != null) {
            onLane(previous.userId(), lane -> {
                lane.riskSnapshots.remove(positionKey);
                publishRiskSnapshot(positionKey, null);
                return null;
            });
        } else {
            publishedRiskSnapshots.removeKey(positionKey);
        }
        if (laneCommandScope.get() == null) changedRiskSnapshots.put(positionKey, null);
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
            lane.putOrder(terminalOrder);
            lane.reservations.put(orderId, released);
            captureBalanceAfter(lane, userId, reservation.assetId());
            return new CanceledOrder(terminalOrder, released);
        });
        publishOrder(orderId, canceled.order());
        publishReservation(orderId, canceled.reservation());
        if (matcherSettlementChangesScope.get() == null) {
            changedOrder(orderId, canceled.order());
            changedReservations.add(orderId);
            changedUsers.add(userId);
            changedBalance(userId, canceled.reservation().assetId());
        }
        advanceUserRevision(userId);
    }

    void cancelOrderInLane(long userId, long orderId) {
        AccountLaneState lane = laneCommandScope.get();
        if (lane == null || lane.laneId() != topology.accountLaneId(userId)
                || matcherSettlementChangesScope.get() == null) {
            throw new IllegalStateException("cancel must execute in its owning Account Lane");
        }
        OrderRuntime order = lane.orders.get(orderId);
        ReservationRuntime reservation = lane.reservations.get(orderId);
        if (order == null || reservation == null || order.userId() != userId || order.status().terminal()) {
            throw new IllegalArgumentException("runtime order is not cancelable: " + orderId);
        }
        cancelOrder(orderId, userId, reservation.reservedUnits());
    }

    void replaceOrderInLane(AccountLaneState lane, long userId, long originalOrderId,
                            ResolvedPlaceOrder replacement, java.util.UUID commandId,
                            long requiredReservation, long clientKey, int symbolId, int assetId,
                            long coreSequence) {
        if (lane == null || laneCommandScope.get() != lane || matcherSettlementChangesScope.get() == null
                || lane.laneId() != topology.accountLaneId(userId)) {
            throw new IllegalStateException("replace must execute in its owning Account Lane");
        }
        cancelOrderInLane(userId, originalOrderId);
        captureBalanceBefore(userId, assetId);
        placeOrderProvisionalInLane(lane, userId, replacement, commandId, requiredReservation,
                clientKey, symbolId, assetId, coreSequence);
        publishUser(userId, lane.users.get(userId));
        publishOrder(replacement.orderId(), lane.orders.get(replacement.orderId()));
        publishReservation(replacement.orderId(), lane.reservations.get(replacement.orderId()));
        captureBalanceAfter(lane, userId, assetId);
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
            captureBalanceAfter(lane, order.userId(), reservation.assetId());
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
                lane.removeOrder(prune.orderId());
                if (prune.clientKey() != 0) {
                    LongLongHashMap clients = lane.clientOrderIndex.get(prune.userId());
                    if (clients != null && clients.containsKey(prune.clientKey())
                            && clients.get(prune.clientKey()) == prune.orderId()) {
                        removeClientOrderIndex(lane, prune.userId(), prune.clientKey());
                    }
                }
                pruned.add(new TerminalOrderPruned(prune.orderId(), prune.userId(), reservationAssetId,
                        reservation != null));
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
                changedOrder(terminal.orderId(), null);
                changedUsers.add(terminal.userId());
                if (terminal.reservationRemoved()) {
                    publishReservation(terminal.orderId(), null);
                    reservationLaneIds.removeKey(terminal.orderId());
                    changedReservations.add(terminal.orderId());
                    changedBalance(terminal.userId(), terminal.reservationAssetId());
                }
            }
        }
    }

    public LongHashSet changedUsers() {
        assertOwner();
        return new LongHashSet(changedUsers);
    }

    public void acceptChangedUserIds(java.util.function.LongConsumer consumer) {
        assertOwner();
        if (consumer == null) throw new IllegalArgumentException("changed user consumer is required");
        changedUsers.forEach(consumer::accept);
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

    private void changedOrder(long orderId) {
        changedOrders.put(orderId, order(orderId));
    }

    private void changedOrder(long orderId, OrderRuntime order) {
        changedOrders.put(orderId, order);
    }

    private void changedPosition(long positionKey) {
        changedPositions.put(positionKey, position(positionKey));
    }

    private void changedPosition(long positionKey, PositionRuntime position) {
        changedPositions.put(positionKey, position);
    }

    public LongHashSet changedOrders() {
        assertOwner();
        LongHashSet keys = new LongHashSet();
        changedOrders.forEach((orderId, ignored) -> keys.add(orderId));
        return keys;
    }

    public LongHashSet changedReservations() {
        assertOwner();
        return new LongHashSet(changedReservations);
    }

    public LongHashSet changedPositions() {
        assertOwner();
        LongHashSet keys = new LongHashSet();
        changedPositions.forEach((positionKey, ignored) -> keys.add(positionKey));
        return keys;
    }

    public org.eclipse.collections.api.iterator.LongIterator changedPositionIterator() {
        assertOwner();
        return changedPositions.longIterator();
    }

    public boolean hasChangedPositions() {
        assertOwner();
        return !changedPositions.isEmpty();
    }

    void visitChangedIndexes(RuntimeFactFrame.ChangeConsumer consumer) {
        assertOwner();
        if (consumer == null) throw new IllegalArgumentException("changed-index consumer is required");
        changedOrders.forEach((orderId, value) -> {
            if (!changedActiveOrderValues.containsKey(orderId)) consumer.order(orderId, null, value);
        });
        changedPositions.forEach((positionKey, value) -> {
            if (!changedPositionIndexValues.containsKey(positionKey)) consumer.position(positionKey, null, value);
        });
        changedLiquidations.forEach((liquidationId, value) ->
                consumer.liquidation(liquidationId, null, value));
        changedRiskSnapshots.forEach((riskKey, value) -> consumer.riskSnapshot(riskKey, null, value));
        changedAlgoOrders.forEach(algoOrderId -> consumer.algoOrder(algoOrderId, null, algoOrder(algoOrderId)));
        changedTriggerOrders.forEach(triggerOrderId ->
                consumer.triggerOrder(triggerOrderId, null, triggerOrder(triggerOrderId)));
        changedCancelAllAfterTimers.forEach(key -> consumer.timer(key, null, cancelAllAfterTimer(key)));
    }

    void visitPreparedMatcherIndexes(RuntimeFactIndexes indexes) {
        assertOwner();
        changedActiveOrderValues.forEach(indexes::preparedOrder);
        changedPositionIndexValues.forEach(indexes::preparedPosition);
    }

    public void releaseRetiredPositionIdentities(RuntimeIdentityRegistry identities) {
        assertOwner();
        if (identities == null) throw new IllegalArgumentException("runtime identities are required");
        changedPositions.forEach((positionKey, ignored) ->
                releaseRetiredPositionIdentity(identities, positionKey));
        changedRiskSnapshots.forEach((positionKey, ignored) ->
                releaseRetiredPositionIdentity(identities, positionKey));
    }

    private void releaseRetiredPositionIdentity(RuntimeIdentityRegistry identities, long positionKey) {
        if (publishedPositions.get(positionKey) == null && publishedRiskSnapshots.get(positionKey) == null) {
            identities.releasePositionKey(positionKey);
        }
    }

    public long committedRevision() {
        assertOwner();
        return Math.subtractExact(revision, totalPendingReservations);
    }

    public RuntimeFundsDelta prepareFundsDelta() {
        assertOwner();
        ArrayList<RuntimeFundsDelta.Posting> postings = new ArrayList<>();
        addBalancePostings(postings, patchBalancesBeforeByLane);
        treasury.changedAssets().forEach(assetId -> {
            RuntimeFactFrame.TreasuryAssetValue before = treasury.patchAssetBefore(assetId);
            RuntimeFactFrame.TreasuryAssetValue after = treasuryAssetValue(assetId);
            addFundsPosting(postings, assetId, FundsPosting.OwnerKind.TREASURY, 0,
                    FundsPosting.Subledger.FEE, Math.subtractExact(fee(after), fee(before)));
            addFundsPosting(postings, assetId, FundsPosting.OwnerKind.TREASURY, 0,
                    FundsPosting.Subledger.INSURANCE,
                    Math.subtractExact(insurance(after), insurance(before)));
            addFundsPosting(postings, assetId, FundsPosting.OwnerKind.TREASURY, 0,
                    FundsPosting.Subledger.DEFICIT,
                    Math.negateExact(Math.subtractExact(deficit(after), deficit(before))));
            addFundsPosting(postings, assetId, FundsPosting.OwnerKind.TREASURY, 0,
                    FundsPosting.Subledger.LIQUIDATION_FEE,
                    Math.subtractExact(liquidationFee(after), liquidationFee(before)));
            addFundsPosting(postings, assetId, FundsPosting.OwnerKind.TREASURY, 0,
                    FundsPosting.Subledger.FUNDING_RESIDUAL,
                    Math.subtractExact(fundingResidual(after), fundingResidual(before)));
            addFundsPosting(postings, assetId, FundsPosting.OwnerKind.TREASURY, 0,
                    FundsPosting.Subledger.ROUNDING_RESIDUAL,
                    Math.subtractExact(roundingResidual(after), roundingResidual(before)));
            addFundsPosting(postings, assetId, FundsPosting.OwnerKind.TREASURY, 0,
                    FundsPosting.Subledger.CLEARING_PNL,
                    Math.subtractExact(clearingPnl(after), clearingPnl(before)));
        });
        return RuntimeFundsDelta.fromDistinct(postings);
    }

    private static void prepareBalanceFundsDelta(LaneBalancePatches patches,
                                                 RuntimeFundsAccumulator accumulator) {
        accumulator.clear();
        for (int index = 0; index < patches.size(); index++) {
            long userId = patches.userId(index);
            int assetId = patches.assetId(index);
            RuntimeFactFrame.UserBalance before = patches.before(index);
            RuntimeFactFrame.UserBalance after = patches.after(index);
            accumulator.add(assetId, FundsPosting.OwnerKind.USER, userId,
                    FundsPosting.Subledger.AVAILABLE,
                    Math.subtractExact(available(after), available(before)));
            accumulator.add(assetId, FundsPosting.OwnerKind.USER, userId,
                    FundsPosting.Subledger.LOCKED,
                    Math.subtractExact(locked(after), locked(before)));
        }
    }

    private static void addBalancePostings(ArrayList<RuntimeFundsDelta.Posting> postings,
                                           LaneBalancePatches[] patches) {
        for (LaneBalancePatches balances : patches) {
            addBalancePostings(postings, balances);
        }
    }

    private static void addBalancePostings(ArrayList<RuntimeFundsDelta.Posting> postings,
                                           LaneBalancePatches balances) {
        for (int index = 0; index < balances.size(); index++) {
            long userId = balances.userId(index);
            int assetId = balances.assetId(index);
            RuntimeFactFrame.UserBalance before = balances.before(index);
            RuntimeFactFrame.UserBalance after = balances.after(index);
            addFundsPosting(postings, assetId, FundsPosting.OwnerKind.USER, userId,
                    FundsPosting.Subledger.AVAILABLE,
                    Math.subtractExact(available(after), available(before)));
            addFundsPosting(postings, assetId, FundsPosting.OwnerKind.USER, userId,
                    FundsPosting.Subledger.LOCKED,
                    Math.subtractExact(locked(after), locked(before)));
        }
    }

    private static void addFundsPosting(ArrayList<RuntimeFundsDelta.Posting> postings, int assetId,
                                        FundsPosting.OwnerKind ownerKind, long ownerId,
                                        FundsPosting.Subledger subledger, long units) {
        if (units != 0) postings.add(new RuntimeFundsDelta.Posting(
                assetId, ownerKind, ownerId, subledger, units));
    }

    private static long available(RuntimeFactFrame.UserBalance value) {
        return value == null ? 0 : value.availableUnits();
    }

    private static long locked(RuntimeFactFrame.UserBalance value) {
        return value == null ? 0 : value.lockedUnits();
    }

    private static long fee(RuntimeFactFrame.TreasuryAssetValue value) {
        return value == null ? 0 : value.fee();
    }

    private static long insurance(RuntimeFactFrame.TreasuryAssetValue value) {
        return value == null ? 0 : value.insurance();
    }

    private static long deficit(RuntimeFactFrame.TreasuryAssetValue value) {
        return value == null ? 0 : value.deficit();
    }

    private static long liquidationFee(RuntimeFactFrame.TreasuryAssetValue value) {
        return value == null ? 0 : value.liquidationFee();
    }

    private static long fundingResidual(RuntimeFactFrame.TreasuryAssetValue value) {
        return value == null ? 0 : value.fundingResidual();
    }

    private static long roundingResidual(RuntimeFactFrame.TreasuryAssetValue value) {
        return value == null ? 0 : value.roundingResidual();
    }

    private static long clearingPnl(RuntimeFactFrame.TreasuryAssetValue value) {
        return value == null ? 0 : value.clearingPnl();
    }

    public PositionRuntime currentPatchPositionBefore(long positionKey) {
        assertOwner();
        PositionRuntime current = position(positionKey);
        if (current != null) {
            int laneId = topology.accountLaneId(current.userId());
            LaneLongCaptures<PositionRuntime> captured = patchPositionsBeforeByLane[laneId];
            return captured.containsKey(positionKey) ? captured.get(positionKey) : current;
        }
        for (LaneLongCaptures<PositionRuntime> captured : patchPositionsBeforeByLane) {
            if (captured.containsKey(positionKey)) {
                return captured.get(positionKey);
            }
        }
        return null;
    }

    public OrderRuntime currentPatchOrderBefore(long orderId) {
        assertOwner();
        PatchOrderBefore captured = capturedOrderBefore(orderId);
        return captured == null ? order(orderId) : captured.value();
    }

    private RuntimeFactFrame.TreasuryAssetValue treasuryAssetValue(int assetId) {
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
        return new RuntimeFactFrame.TreasuryAssetValue(fee, insurance, deficit, liquidationFee,
                fundingResidual, roundingResidual, clearingPnl);
    }

    public void clearChangedKeys() {
        assertOwner();
        removeChangedMapEntries(changedBalances, changedUsers);
        for (LaneLongCaptures<?> captured : patchUsersBeforeByLane) captured.clear();
        for (LaneLongCaptures<?> captured : patchReservationsBeforeByLane) captured.clear();
        for (LaneLongCaptures<?> captured : patchOrdersBeforeByLane) captured.clear();
        for (LaneLongCaptures<?> captured : patchPositionsBeforeByLane) captured.clear();
        for (LaneClientOrderCaptures captured : patchClientOrdersBeforeByLane) captured.clear();
        clearChanged(changedUsers);
        changedOrders.clear();
        changedActiveOrderValues.clear();
        clearChanged(changedReservations);
        changedPositions.clear();
        changedPositionIndexValues.clear();
        changedLiquidations.clear();
        clearChanged(changedMarkPrices);
        changedRiskSnapshots.clear();
        clearChanged(changedRiskScans);
        changedInstruments.clear();
        changedLeverages.clear();
        clearChanged(changedAlgoOrders);
        changedCancelAllAfterTimers.clear();
        clearChanged(changedTriggerOrders);
        clearChanged(changedFeePolicies);
        treasury.clearChangedKeys();
        for (LaneBalancePatches capturedBalances : patchBalancesBeforeByLane) capturedBalances.clear();
        patchLiquidationsBefore = clearCapturedChanges(patchLiquidationsBefore);
        patchRiskSnapshotsBefore = clearCapturedChanges(patchRiskSnapshotsBefore);
        patchLeveragesBefore = clearCapturedChanges(patchLeveragesBefore);
        patchAlgoOrdersBefore = clearCapturedChanges(patchAlgoOrdersBefore);
        patchTriggerOrdersBefore = clearCapturedChanges(patchTriggerOrdersBefore);
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

    private static <T> void removeChangedMapEntries(LongObjectHashMap<T> values, LongHashSet changedKeys) {
        changedKeys.forEach(values::removeKey);
        if (!values.isEmpty()) {
            throw new IllegalStateException("changed map contains an untracked key");
        }
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

    LongObjectHashMap<LongLongHashMap> clientOrderIndexForSnapshot() {
        LongObjectHashMap<LongLongHashMap> values = new LongObjectHashMap<>();
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
            LongLongHashMap userClientOrders = lane.clientOrderIndex.get(userId);
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
            lane.putOrder(order);
            lane.reservations.put(orderId, reservation);
            addUserEntity(lane.reservationIdsByUser, userId, orderId);
            if (clientKey != 0) putClientOrderIndex(lane, userId, clientKey, orderId);
            captureBalanceAfter(lane, userId, assetId);
            return new ReservedOrder(order, reservation);
        });
        publishOrder(orderId, reserved.order());
        publishReservation(orderId, reserved.reservation());
        orderLaneIds.put(orderId, topology.accountLaneId(userId) + 1L);
        reservationLaneIds.put(orderId, topology.accountLaneId(userId) + 1L);
        changedOrder(orderId, reserved.order());
        changedReservations.add(orderId);
        changedUsers.add(userId);
        changedBalance(userId, assetId);
    }

    /**
     * Lane-owned provisional PLACE mutation. Admission is not externally visible until the owner
     * collects the event, so it deliberately creates no checkpoint, changed-key or commit-patch state.
     */
    void placeOrderProvisionalInLane(
            AccountLaneState lane, long userId, ResolvedPlaceOrder command, java.util.UUID commandId,
            long requiredReservation, long clientKey, int symbolId, int assetId, long coreSequence) {
        if (lane == null || laneCommandScope.get() != lane
                || lane.laneId() != topology.accountLaneId(userId)
                || command == null || commandId == null || userId <= 0 || requiredReservation <= 0
                || clientKey < 0 || symbolId < 0 || assetId < 0 || coreSequence <= 0) {
            throw new IllegalArgumentException("invalid Lane-owned provisional place order");
        }
        lane.assertOwner();
        if (lane.orders.containsKey(command.orderId())) {
            throw new CoreStateRejectedException("DUPLICATE_ORDER_ID", "orderId already exists");
        }
        LongLongHashMap userClientOrders = lane.clientOrderIndex.get(userId);
        if (clientKey != 0 && userClientOrders != null && userClientOrders.containsKey(clientKey)) {
            throw new CoreStateRejectedException("DUPLICATE_CLIENT_ORDER_ID", "clientOrderId already exists");
        }
        UserRuntime user = lane.users.get(userId);
        IntObjectHashMap<BalanceRuntime> balances = lane.balances.get(userId);
        BalanceRuntime balance = balances == null ? null : balances.get(assetId);
        if (user == null || balance == null || balance.availableUnits() < requiredReservation) {
            throw new CoreStateRejectedException("INSUFFICIENT_AVAILABLE_BALANCE",
                    "available balance is insufficient");
        }
        OrderRuntime order = new OrderRuntime(command.orderId(), productLine, userId, symbolId,
                command.instrumentVersion(), command.side(), command.limitPriceTicks(), command.matchingPriceTicks(),
                command.quantitySteps(), 0, command.quantitySteps(), command.reduceOnly(), command.marginMode(),
                command.positionSide(), command.orderType(), command.timeInForce(), command.postOnly(),
                command.clientOrderId(), commandId, command.makerFeeRatePpm(), command.takerFeeRatePpm(),
                0, 0, 0, CoreOrderStatus.OPEN, 1);
        ReservationRuntime reservation = new ReservationRuntime(command.orderId(), userId, symbolId,
                command.instrumentVersion(), command.reservationKind(), assetId, requiredReservation,
                0, 0, command.quantitySteps());
        balance.reserve(requiredReservation);
        lane.putOrder(order);
        lane.reservations.put(reservation.orderId(), reservation);
        addUserEntity(lane.reservationIdsByUser, userId, order.orderId());
        if (clientKey != 0) putClientOrderIndex(lane, userId, clientKey, order.orderId());
        UserRuntime advanced = new UserRuntime(productLine, userId,
                Math.incrementExact(user.revision()), user.positionMode());
        lane.users.put(userId, advanced);
        lane.markPendingReservation(order.orderId(), coreSequence);
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
        if (matcherSettlementChangesScope.get() == null) {
            positionLaneIds.put(positionKey, topology.accountLaneId(position.userId()) + 1L);
            changedPosition(positionKey, position);
            changedUsers.add(position.userId());
            if (previous != null) changedUsers.add(previous.userId());
        }
    }

    private static void indexPosition(AccountLaneState lane, long positionKey, PositionRuntime position) {
        addUserEntity(lane.positionKeysByUser, position.userId(), positionKey);
        if (position.signedQuantitySteps() == 0) return;
        LongObjectHashMap<LongHashSet> byUser = lane.positionKeysBySymbolAndUser.get(position.symbolId());
        if (byUser == null) {
            byUser = new LongObjectHashMap<>();
            lane.positionKeysBySymbolAndUser.put(position.symbolId(), byUser);
        }
        LongHashSet keys = byUser.get(position.userId());
        if (keys == null) {
            keys = new LongHashSet();
            byUser.put(position.userId(), keys);
        }
        keys.add(positionKey);
    }

    private static void unindexPosition(AccountLaneState lane, long positionKey, PositionRuntime position) {
        removeUserEntity(lane.positionKeysByUser, position.userId(), positionKey);
        LongObjectHashMap<LongHashSet> byUser = lane.positionKeysBySymbolAndUser.get(position.symbolId());
        LongHashSet keys = byUser == null ? null : byUser.get(position.userId());
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

    private static TreeSet<Long> toSortedSet(LongHashSet values) {
        TreeSet<Long> sorted = new TreeSet<>();
        values.forEach(sorted::add);
        return sorted;
    }

    private void captureUserBefore(long userId) {
        if (matcherSettlementChangesScope.get() != null) return;
        LaneLongCaptures<UserRuntime> captured =
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

    private void rollbackBalance(long userId, int assetId, RuntimeFactFrame.UserBalance before) {
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
        if (current != null) onLane(current.userId(), lane -> { lane.removeOrder(orderId); return null; });
        if (before == null) {
            publishOrder(orderId, null);
            orderLaneIds.removeKey(orderId);
        } else {
            onLane(before.userId(), lane -> { lane.putOrder(before); return null; });
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
        PositionRuntime before = laneId >= 0 && patchPositionsBeforeByLane[laneId].containsKey(positionKey)
                ? patchPositionsBeforeByLane[laneId].get(positionKey) : null;
        if (current == null) {
            for (int candidateLaneId = 0; candidateLaneId < accountLanes.length; candidateLaneId++) {
                if (patchPositionsBeforeByLane[candidateLaneId].containsKey(positionKey)) {
                    before = patchPositionsBeforeByLane[candidateLaneId].get(positionKey);
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
        MatcherSettlementChanges changes = matcherSettlementChangesScope.get();
        LaneBalancePatches captured = changes == null
                ? patchBalancesBeforeByLane[topology.accountLaneId(userId)]
                : changes.balancePatches[topology.accountLaneId(userId)];
        if (captured.contains(userId, assetId)) return;
        AccountLaneState scoped = laneCommandScope.get();
        if (scoped != null) {
            captureBalanceBefore(scoped, captured, userId, assetId);
            return;
        }
        onLane(userId, lane -> {
            captureBalanceBefore(lane, captured, userId, assetId);
            return null;
        });
    }

    private static void captureBalanceBefore(AccountLaneState lane, LaneBalancePatches captured,
                                             long userId, int assetId) {
        IntObjectHashMap<BalanceRuntime> balances = lane.balances.get(userId);
        BalanceRuntime balance = balances == null ? null : balances.get(assetId);
        captured.add(userId, assetId, balance, lane.pendingReservedUnits(userId, assetId));
    }

    private void captureBalanceAfter(AccountLaneState lane, long userId, int assetId) {
        MatcherSettlementChanges changes = matcherSettlementChangesScope.get();
        LaneBalancePatches captured = changes == null
                ? patchBalancesBeforeByLane[lane.laneId()]
                : changes.balancePatches[lane.laneId()];
        IntObjectHashMap<BalanceRuntime> balances = lane.balances.get(userId);
        BalanceRuntime balance = balances == null ? null : balances.get(assetId);
        captured.after(userId, assetId, balance, lane.pendingReservedUnits(userId, assetId));
    }

    private void captureOrderBefore(long orderId) {
        if (matcherSettlementChangesScope.get() != null) return;
        if (capturedOrderBefore(orderId) != null) return;
        OrderRuntime value = order(orderId);
        int laneId = captureLane(orderId, value == null ? 0 : value.userId(), orderLaneIds);
        LaneLongCaptures<PatchOrderBefore> captured = patchOrdersBeforeByLane[laneId];
        if (!captured.containsKey(orderId)) {
            captured.put(orderId, new PatchOrderBefore(value,
                    value != null && pendingReservation(orderId, value.userId())));
        }
    }

    private void captureReservationBefore(long orderId) {
        if (matcherSettlementChangesScope.get() != null) return;
        if (capturedReservationBefore(orderId) != null) return;
        ReservationRuntime value = reservation(orderId);
        int laneId = captureLane(orderId, value == null ? 0 : value.userId(), reservationLaneIds);
        LaneLongCaptures<PatchReservationBefore> captured = patchReservationsBeforeByLane[laneId];
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
        for (LaneLongCaptures<PatchOrderBefore> captured : patchOrdersBeforeByLane) {
            PatchOrderBefore value = captured.get(orderId);
            if (value != null || captured.containsKey(orderId)) return value;
        }
        return null;
    }

    private PatchReservationBefore capturedReservationBefore(long orderId) {
        for (LaneLongCaptures<PatchReservationBefore> captured : patchReservationsBeforeByLane) {
            PatchReservationBefore value = captured.get(orderId);
            if (value != null || captured.containsKey(orderId)) return value;
        }
        return null;
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
        if (matcherSettlementChangesScope.get() != null) return;
        PositionRuntime before = position(positionKey);
        long userId = before == null ? fallbackUserId : before.userId();
        if (userId > 0) {
            LaneLongCaptures<PositionRuntime> captured =
                    patchPositionsBeforeByLane[topology.accountLaneId(userId)];
            if (!captured.containsKey(positionKey)) captured.put(positionKey, before);
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
        if (matcherSettlementChangesScope.get() != null) return;
        LaneClientOrderCaptures captured = patchClientOrdersBeforeByLane[topology.accountLaneId(userId)];
        if (!captured.contains(userId, clientKey)) {
            captured.add(userId, clientKey, orderIdByClient(userId, clientKey));
        }
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
        LongLongHashMap userClientOrders = lane.clientOrderIndex.get(userId);
        if (userClientOrders == null) {
            userClientOrders = new LongLongHashMap();
            lane.clientOrderIndex.put(userId, userClientOrders);
        }
        boolean hadPrevious = userClientOrders.containsKey(clientKey);
        long previousOrderId = hadPrevious ? userClientOrders.get(clientKey) : 0;
        userClientOrders.put(clientKey, orderId);
        if (hadPrevious && previousOrderId != orderId) {
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
        LongLongHashMap userClientOrders = lane.clientOrderIndex.get(userId);
        if (userClientOrders == null || !userClientOrders.containsKey(clientKey)) return null;
        long orderId = userClientOrders.get(clientKey);
        userClientOrders.remove(clientKey);
        if (userClientOrders.isEmpty()) lane.clientOrderIndex.remove(userId);
        removeClientOrderReverse(lane, orderId, clientKey);
        return orderId;
    }

    private static void removeClientOrderReverse(AccountLaneState lane, long orderId, long clientKey) {
        LongHashSet keys = lane.clientKeysByOrderId.get(orderId);
        if (keys == null) return;
        keys.remove(clientKey);
        if (keys.isEmpty()) lane.clientKeysByOrderId.remove(orderId);
    }

    private static void removeClientOrdersForOrder(AccountLaneState lane, long userId, long orderId) {
        LongHashSet keys = lane.clientKeysByOrderId.remove(orderId);
        if (keys == null) return;
        LongLongHashMap userClientOrders = lane.clientOrderIndex.get(userId);
        if (userClientOrders == null) return;
        keys.forEach(userClientOrders::removeKey);
        if (userClientOrders.isEmpty()) lane.clientOrderIndex.remove(userId);
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

    private static final class LaneClientOrderCaptures {
        private long[] userIds = new long[4];
        private long[] clientKeys = new long[4];
        private long[] beforeOrderIds = new long[4];
        private boolean[] presentBefore = new boolean[4];
        private int size;

        private int size() { return size; }
        private long userId(int index) { return userIds[index]; }
        private long clientKey(int index) { return clientKeys[index]; }
        private Long beforeOrderId(int index) {
            return presentBefore[index] ? beforeOrderIds[index] : null;
        }

        private boolean contains(long userId, long clientKey) {
            for (int index = 0; index < size; index++) {
                if (userIds[index] == userId && clientKeys[index] == clientKey) return true;
            }
            return false;
        }

        private void add(long userId, long clientKey, Long beforeOrderId) {
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

        private void clear() {
            for (int index = 0; index < size; index++) presentBefore[index] = false;
            size = 0;
        }
    }

    private static final class LaneBalancePatches {
        private long[] userIds = new long[8];
        private int[] assetIds = new int[8];
        private long[] availableBefore = new long[8];
        private long[] lockedBefore = new long[8];
        private long[] pendingBefore = new long[8];
        private boolean[] presentBefore = new boolean[8];
        private long[] availableAfter = new long[8];
        private long[] lockedAfter = new long[8];
        private long[] pendingAfter = new long[8];
        private boolean[] presentAfter = new boolean[8];
        private boolean[] capturedAfter = new boolean[8];
        private long[] indexUserIds = new long[16];
        private int[] indexAssetIds = new int[16];
        private int[] indexSlots = new int[16];
        private int[] indexGenerations = new int[16];
        private int indexGeneration = 1;
        private int size;

        private int size() { return size; }
        private long userId(int index) { return userIds[index]; }
        private int assetId(int index) { return assetIds[index]; }
        private RuntimeFactFrame.UserBalance before(int index) {
            return !presentBefore[index] ? null : new RuntimeFactFrame.UserBalance(
                    availableBefore[index], lockedBefore[index], pendingBefore[index]);
        }

        private RuntimeFactFrame.UserBalance after(int index) {
            if (!capturedAfter[index]) {
                throw new IllegalStateException("balance mutation did not publish its lane after-state");
            }
            return !presentAfter[index] ? null : new RuntimeFactFrame.UserBalance(
                    availableAfter[index], lockedAfter[index], pendingAfter[index]);
        }

        private boolean contains(long userId, int assetId) {
            return indexOf(userId, assetId) >= 0;
        }

        private void add(long userId, int assetId, BalanceRuntime value, long pendingReservedUnits) {
            if (indexOf(userId, assetId) >= 0) {
                throw new IllegalStateException("balance capture key already exists");
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

        private void after(long userId, int assetId, BalanceRuntime value, long pendingReservedUnits) {
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

        private void clear() {
            size = 0;
            if (++indexGeneration == 0) {
                java.util.Arrays.fill(indexGenerations, 0);
                indexGeneration = 1;
            }
        }

        private int indexOf(long userId, int assetId) {
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

        private int emptyIndexPosition(long userId, int assetId) {
            int mask = indexSlots.length - 1;
            int position = pairHash(userId, assetId) & mask;
            while (indexGenerations[position] == indexGeneration) position = (position + 1) & mask;
            return position;
        }

        private void ensureIndexCapacity(int requiredSize) {
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

        private static int pairHash(long userId, int assetId) {
            long value = userId ^ Integer.toUnsignedLong(assetId) * 0x9e3779b97f4a7c15L;
            value ^= value >>> 33;
            value *= 0xff51afd7ed558ccdL;
            value ^= value >>> 33;
            return (int) value;
        }
    }

    /**
     * Per-command capture buffer. Keys stay primitive and only the slots touched by the current
     * command are visited; unlike an open-addressed map, historical capacity never becomes scan
     * work on the owner hot path.
     */
    private static final class LaneLongCaptures<V> {
        private long[] keys = new long[8];
        private Object[] values = new Object[8];
        private long[] indexKeys = new long[16];
        private int[] indexSlots = new int[16];
        private int[] indexGenerations = new int[16];
        private int indexGeneration = 1;
        private int size;

        private int size() { return size; }
        private boolean isEmpty() { return size == 0; }

        private boolean containsKey(long key) { return indexOf(key) >= 0; }

        private V get(long key) {
            int index = indexOf(key);
            return index < 0 ? null : value(index);
        }

        private void put(long key, V value) {
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

        private long key(int index) {
            if (index < 0 || index >= size) throw new IndexOutOfBoundsException(index);
            return keys[index];
        }

        @SuppressWarnings("unchecked")
        private V value(int index) {
            if (index < 0 || index >= size) throw new IndexOutOfBoundsException(index);
            return (V) values[index];
        }

        private int indexOf(long key) {
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
            while (indexGenerations[position] == indexGeneration) position = (position + 1) & mask;
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

        private static int longHash(long key) {
            key ^= key >>> 33;
            key *= 0xff51afd7ed558ccdL;
            key ^= key >>> 33;
            return (int) key;
        }

        private void clear() {
            for (int index = 0; index < size; index++) values[index] = null;
            size = 0;
            if (++indexGeneration == 0) {
                java.util.Arrays.fill(indexGenerations, 0);
                indexGeneration = 1;
            }
        }
    }

    static final class PendingReservationSequenceIndex {
        private static final long[] EMPTY_ORDER_IDS = new long[0];

        private final LongLongHashMap firstOrderBySequence;
        private final LongObjectHashMap<LongHashSet> additionalOrdersBySequence;

        PendingReservationSequenceIndex(int initialCapacity) {
            firstOrderBySequence = new LongLongHashMap(initialCapacity);
            additionalOrdersBySequence = new LongObjectHashMap<>();
        }

        void add(long coreSequence, long orderId) {
            long firstOrderId = firstOrderBySequence.getIfAbsent(coreSequence, 0);
            if (firstOrderId == 0) {
                firstOrderBySequence.put(coreSequence, orderId);
                return;
            }
            if (firstOrderId == orderId) {
                throw new IllegalStateException("reservation is already indexed for sequence");
            }
            LongHashSet additionalOrderIds = additionalOrdersBySequence.get(coreSequence);
            if (additionalOrderIds == null) {
                additionalOrderIds = new LongHashSet();
                additionalOrdersBySequence.put(coreSequence, additionalOrderIds);
            }
            if (!additionalOrderIds.add(orderId)) {
                throw new IllegalStateException("reservation is already indexed for sequence");
            }
        }

        boolean containsKey(long coreSequence) {
            return firstOrderBySequence.containsKey(coreSequence);
        }

        boolean contains(long coreSequence, long orderId) {
            long firstOrderId = firstOrderBySequence.getIfAbsent(coreSequence, 0);
            if (firstOrderId == orderId) return orderId != 0;
            LongHashSet additionalOrderIds = additionalOrdersBySequence.get(coreSequence);
            return additionalOrderIds != null && additionalOrderIds.contains(orderId);
        }

        long[] orderIds(long coreSequence) {
            long firstOrderId = firstOrderBySequence.getIfAbsent(coreSequence, 0);
            if (firstOrderId == 0) return EMPTY_ORDER_IDS;
            LongHashSet additionalOrderIds = additionalOrdersBySequence.get(coreSequence);
            if (additionalOrderIds == null || additionalOrderIds.isEmpty()) {
                return new long[]{firstOrderId};
            }
            long[] additional = additionalOrderIds.toArray();
            long[] orderIds = new long[additional.length + 1];
            orderIds[0] = firstOrderId;
            System.arraycopy(additional, 0, orderIds, 1, additional.length);
            return orderIds;
        }

        void remove(long coreSequence, long orderId) {
            long firstOrderId = firstOrderBySequence.getIfAbsent(coreSequence, 0);
            if (firstOrderId == 0) {
                throw new IllegalStateException("pending reservation sequence is missing");
            }
            LongHashSet additionalOrderIds = additionalOrdersBySequence.get(coreSequence);
            if (firstOrderId != orderId) {
                if (additionalOrderIds == null || !additionalOrderIds.remove(orderId)) {
                    throw new IllegalStateException("pending reservation is missing from sequence");
                }
                if (additionalOrderIds.isEmpty()) additionalOrdersBySequence.removeKey(coreSequence);
                return;
            }
            if (additionalOrderIds == null || additionalOrderIds.isEmpty()) {
                firstOrderBySequence.removeKey(coreSequence);
                return;
            }
            long promotedOrderId = additionalOrderIds.toArray()[0];
            additionalOrderIds.remove(promotedOrderId);
            firstOrderBySequence.put(coreSequence, promotedOrderId);
            if (additionalOrderIds.isEmpty()) additionalOrdersBySequence.removeKey(coreSequence);
        }

        boolean isEmpty() {
            return firstOrderBySequence.isEmpty();
        }

        void clear() {
            firstOrderBySequence.clear();
            additionalOrdersBySequence.clear();
        }
    }

    private record PatchBefore<T>(T value) {}
    private record PatchOrderBefore(OrderRuntime value, boolean pending) {}
    private record PatchReservationBefore(ReservationRuntime value, boolean pending) {}

}
