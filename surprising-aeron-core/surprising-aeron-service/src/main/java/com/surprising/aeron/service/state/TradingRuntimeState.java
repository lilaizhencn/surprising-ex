package com.surprising.aeron.service.state;

import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.eclipse.collections.impl.set.mutable.primitive.IntHashSet;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreRiskScanControlView;
import com.surprising.aeron.service.matching.CoreMatchingResult;
import com.surprising.aeron.service.AccountLaneAck;
import com.surprising.product.api.ProductLine;
import java.util.Collections;
import java.util.ArrayList;
import java.util.Map;
import java.util.NavigableSet;
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
    private final java.util.concurrent.ExecutorService[] lifecycleLaneExecutors;
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
        this.lifecycleLaneExecutors = new java.util.concurrent.ExecutorService[topology.accountLaneCount()];
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            accountLanes[laneId] = new AccountLaneState(laneId, topology.accountLaneQueueCapacity());
            int ownerLaneId = laneId;
            lifecycleLaneExecutors[laneId] = java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "core-lifecycle-lane-" + ownerLaneId);
                thread.setDaemon(true);
                return thread;
            });
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
        return AccountLaneMetricsSnapshot.empty(accountLanes[laneId].queueCapacity());
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
        boolean[] selected = new boolean[accountLanes.length];
        int selectedCount = 0;
        for (E value : values) {
            if (value == null) continue;
            long userId = ownerUserId.applyAsLong(value);
            if (userId <= 0) continue;
            int laneId = topology.accountLaneId(userId);
            if (!selected[laneId]) {
                selected[laneId] = true;
                selectedCount++;
            }
        }
        if (selectedCount < 2 || !accountLanesStarted) {
            return executeOwnerSettlements(values, ownerUserId, operation);
        }
        Object[] results = new Object[accountLanes.length];
        @SuppressWarnings("unchecked")
        java.util.concurrent.Future<Object>[] futures = new java.util.concurrent.Future[accountLanes.length];
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            if (!selected[laneId]) continue;
            AccountLaneState lane = accountLanes[laneId];
            lane.releaseOwner();
            int currentLaneId = laneId;
            try {
                futures[laneId] = lifecycleLaneExecutors[laneId].submit(() -> {
                    try {
                        return inLaneCommandScope(lane, ignored -> operation.apply(currentLaneId));
                    } finally {
                        lane.releaseOwner();
                    }
                });
            } catch (RuntimeException failure) {
                lane.bindOwner();
                awaitAndRebindLifecycleLanes(selected, futures);
                throw failure;
            }
        }
        RuntimeException failure = null;
        for (int laneId = 0; laneId < futures.length; laneId++) {
            java.util.concurrent.Future<Object> future = futures[laneId];
            if (future == null) continue;
            try {
                results[laneId] = future.get();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                failure = new IllegalStateException("lifecycle lane settlement was interrupted", interrupted);
                break;
            } catch (java.util.concurrent.ExecutionException execution) {
                Throwable cause = execution.getCause();
                failure = cause instanceof RuntimeException runtimeFailure
                        ? runtimeFailure : new IllegalStateException("lifecycle lane settlement failed", cause);
                break;
            }
        }
        awaitAndRebindLifecycleLanes(selected, futures);
        if (failure != null) throw failure;
        return results;
    }

    private void awaitAndRebindLifecycleLanes(boolean[] selected,
                                              java.util.concurrent.Future<Object>[] futures) {
        for (java.util.concurrent.Future<Object> future : futures) {
            if (future == null) continue;
            try {
                future.get();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (java.util.concurrent.ExecutionException ignored) {
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
        for (java.util.concurrent.ExecutorService executor : lifecycleLaneExecutors) executor.shutdownNow();
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
        if (pendingReservationUsers.containsKey(orderId)) {
            throw new IllegalStateException("reservation is already indexed as pending");
        }
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
        totalPendingReservations = Math.incrementExact(totalPendingReservations);
    }

    public void completePendingReservation(long userId, long orderId, long coreSequence) {
        assertOwner();
        PendingReservationCompletion completion = onLane(userId, accountLane -> {
            ReservationRuntime reservation = accountLane.reservations.get(orderId);
            accountLane.completePendingReservation(orderId, coreSequence);
            return new PendingReservationCompletion(reservation, clientKeysForOrder(accountLane, orderId));
        });
        changedOrders.add(orderId);
        changedReservations.add(orderId);
        changedUsers.add(userId);
        if (completion.reservation() != null) changedBalance(userId, completion.reservation().assetId());
        completion.clientKeys().forEach(clientKey -> {
            changedClientOrders.add(clientKey);
            changedClientOrder(userId, clientKey);
        });
        unindexPendingReservation(orderId, coreSequence, userId);
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
        Object[] laneResults = executeOwnerSettlements(refs, PendingReservationRef::userId, laneId -> {
            AccountLaneState lane = laneCommandScope.get();
            List<PendingReservationBatchCompletion> completions = new ArrayList<>();
            for (PendingReservationRef ref : refs) {
                if (topology.accountLaneId(ref.userId()) != laneId) continue;
                ReservationRuntime reservation = lane.reservations.get(ref.orderId());
                lane.completePendingReservation(ref.orderId(), coreSequence);
                completions.add(new PendingReservationBatchCompletion(
                        ref.orderId(), ref.userId(), reservation, clientKeysForOrder(lane, ref.orderId())));
            }
            return List.copyOf(completions);
        });
        for (Object laneResult : laneResults) {
            if (laneResult == null) continue;
            @SuppressWarnings("unchecked")
            List<PendingReservationBatchCompletion> completions =
                    (List<PendingReservationBatchCompletion>) laneResult;
            for (PendingReservationBatchCompletion completion : completions) {
                changedOrders.add(completion.orderId());
                changedReservations.add(completion.orderId());
                changedUsers.add(completion.userId());
                if (completion.reservation() != null) {
                    changedBalance(completion.userId(), completion.reservation().assetId());
                }
                completion.clientKeys().forEach(clientKey -> {
                    changedClientOrders.add(clientKey);
                    changedClientOrder(completion.userId(), clientKey);
                });
                unindexPendingReservation(completion.orderId(), coreSequence, completion.userId());
            }
        }
    }

    private void unindexPendingReservation(long orderId, long coreSequence, long userId) {
        long indexedUserId = pendingReservationUsers.getIfAbsent(orderId, 0);
        LongHashSet orderIds = pendingReservationsBySequence.get(coreSequence);
        if (indexedUserId != userId || orderIds == null || !orderIds.remove(orderId)) {
            throw new IllegalStateException("pending reservation index differs from account lane state");
        }
        pendingReservationUsers.removeKey(orderId);
        if (orderIds.isEmpty()) pendingReservationsBySequence.removeKey(coreSequence);
        totalPendingReservations = Math.decrementExact(totalPendingReservations);
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

    public AccountLaneApplyResult applyAndCommitLaneSequence(long coreSequence, Iterable<Long> userIds,
                                                             CoreMatchingResult matchingResult,
                                                             long stateContribution, long fundsContribution,
                                                             RuntimeTreasuryDelta[] treasuryDeltas) {
        assertOwner();
        if (coreSequence <= 0 || userIds == null || matchingResult == null
                || matchingResult.nativeCommand().coreSequence() != coreSequence
                || treasuryDeltas != null && treasuryDeltas.length != accountLanes.length) {
            throw new IllegalArgumentException("invalid lane apply");
        }
        @SuppressWarnings("unchecked")
        java.util.List<Long>[] routedUsers = new java.util.List[accountLanes.length];
        for (Long userId : userIds) {
            if (userId == null || userId <= 0) continue;
            int laneId = topology.accountLaneId(userId);
            if (routedUsers[laneId] == null) routedUsers[laneId] = new java.util.ArrayList<>();
            routedUsers[laneId].add(userId);
        }
        RuntimeMutationDelta.CaptureRequest captureRequest = mutationCaptureRequest();
        long mask = 0;
        AccountLaneAck[] acknowledgements = new AccountLaneAck[accountLanes.length];
        RuntimeMutationDelta.LaneValues[] laneValues = new RuntimeMutationDelta.LaneValues[accountLanes.length];
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            java.util.List<Long> users = routedUsers[laneId];
            if (users == null) continue;
            mask |= 1L << laneId;
            int currentLaneId = laneId;
            RuntimeTreasuryDelta treasuryDelta = treasuryDeltas == null || treasuryDeltas[laneId] == null
                    ? new RuntimeTreasuryDelta() : treasuryDeltas[laneId];
            AccountLaneState lane = accountLanes[laneId];
            if (matchingResult.nativeCommand().coreSequence() != coreSequence) {
                throw new IllegalStateException("immutable matcher result sequence changed during fanout");
            }
            applyLaneUsers(lane, users, coreSequence, stateContribution, fundsContribution);
            lane.committed(coreSequence);
            acknowledgements[laneId] = new AccountLaneAck(coreSequence, currentLaneId, lane.revision(),
                    lane.localStateHash(), lane.localFundsHash(), matchingResult, treasuryDelta);
            laneValues[laneId] = RuntimeMutationDelta.captureLane(lane, captureRequest);
        }
        return new AccountLaneApplyResult(mask, acknowledgements, captureRequest, laneValues);
    }

    public static final class AccountLaneApplyResult {
        private final long laneMask;
        private final AccountLaneAck[] acknowledgements;
        private final RuntimeMutationDelta.CaptureRequest captureRequest;
        private final RuntimeMutationDelta.LaneValues[] laneValues;

        private AccountLaneApplyResult(long laneMask, AccountLaneAck[] acknowledgements,
                                       RuntimeMutationDelta.CaptureRequest captureRequest,
                                       RuntimeMutationDelta.LaneValues[] laneValues) {
            this.laneMask = laneMask;
            this.acknowledgements = acknowledgements.clone();
            this.captureRequest = captureRequest;
            this.laneValues = laneValues.clone();
        }

        public long laneMask() {
            return laneMask;
        }

        public AccountLaneAck[] acknowledgements() {
            return acknowledgements.clone();
        }
    }

    public RuntimeTreasuryDelta[] applyMatcherSettlement(long coreSequence, long expectedLaneMask,
                                                         long takerOrderId, CoreMatchingResult matchingResult,
                                                         RuntimeIdentityRegistry identities) {
        assertOwner();
        long validMask = accountLanes.length == Long.SIZE ? -1L : (1L << accountLanes.length) - 1L;
        if (coreSequence <= 0 || expectedLaneMask == 0 || (expectedLaneMask & ~validMask) != 0
                || matchingResult == null || matchingResult.nativeCommand().coreSequence() != coreSequence
                || identities == null) {
            throw new IllegalArgumentException("invalid matcher settlement lane command");
        }
        OrderRuntime taker = order(takerOrderId);
        if (taker == null) throw new IllegalStateException("taker order is missing");
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

        RuntimeTreasuryDelta[] deltas = new RuntimeTreasuryDelta[accountLanes.length];
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            if ((expectedLaneMask & (1L << laneId)) == 0) continue;
            AccountLaneState lane = accountLanes[laneId];
            deltas[laneId] = inLaneCommandScope(lane, ignored -> {
                if (!productLine.isDerivative()) {
                    return RuntimeSpotMatchProcessor.applyLane(takerOrderId, matchingResult.matcherEvents(),
                            this, instrument, baseAssetId, quoteAssetId);
                }
                RuntimeTreasuryDelta delta = new RuntimeTreasuryDelta();
                RuntimePerpetualMatchProcessor.applyLane(takerOrderId, matchingResult.matcherEvents(),
                        this, identities, instrument, settleAssetId, delta);
                return delta;
            });
        }
        recordMatcherSettlementChanges(takerOrderId, matchingResult, identities,
                instrument, baseAssetId, quoteAssetId, settleAssetId);
        return deltas;
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

    public java.util.List<AccountLaneSnapshot> accountLaneSnapshots(
            long fenceSequence, TradingCoreState globalState) {
        assertOwner();
        if (globalState == null || globalState.productLine() != productLine) {
            throw new IllegalArgumentException("global snapshot state is required");
        }
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

    public void restoreAccountLaneSnapshots(java.util.List<AccountLaneSnapshot> snapshots, long fenceSequence,
                                            TradingCoreState globalState) {
        assertOwner();
        if (snapshots == null || snapshots.size() != accountLanes.length || globalState == null) {
            throw new IllegalArgumentException("incomplete account lane snapshot set");
        }
        @SuppressWarnings("unchecked")
        java.util.TreeSet<Long>[] expectedUsers = new java.util.TreeSet[accountLanes.length];
        for (int laneId = 0; laneId < expectedUsers.length; laneId++) {
            expectedUsers[laneId] = new java.util.TreeSet<>();
        }
        globalState.users().keySet().forEach(userId ->
                expectedUsers[topology.accountLaneId(userId)].add(userId));
        boolean[] restored = new boolean[accountLanes.length];
        for (AccountLaneSnapshot snapshot : snapshots) {
            int laneId = snapshot.laneId();
            if (laneId < 0 || laneId >= accountLanes.length || restored[laneId]
                    || snapshot.appliedSequence() != fenceSequence
                    || snapshot.committedSequence() != fenceSequence) {
                throw new IllegalArgumentException("invalid account lane snapshot manifest");
            }
            for (Long userId : snapshot.userIds()) {
                if (topology.accountLaneId(userId) != laneId
                        || !onLane(laneId, lane -> lane.users.get(userId) != null)) {
                    throw new IllegalArgumentException("account lane contains an incorrectly routed user");
                }
            }
            if (!expectedUsers[laneId].equals(new java.util.TreeSet<>(snapshot.userIds()))) {
                throw new IllegalArgumentException("account lane user manifest differs from global state");
            }
            restored[laneId] = true;
        }
        for (AccountLaneSnapshot snapshot : snapshots) {
            onLane(snapshot.laneId(), lane -> {
                lane.restore(snapshot);
                return null;
            });
        }
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            onLane(laneId, lane -> {
                lane.users.forEachKey(userId -> {
                    if (!lane.owns(userId)) {
                        throw new IllegalArgumentException("runtime user is absent from its account lane");
                    }
                });
                return null;
            });
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

    void releaseOwnerForHandoff() {
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
        if (riskScanControl == null) throw new IllegalArgumentException("risk scan control is required");
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
        if (instrument == null) {
            throw new IllegalArgumentException("invalid runtime instrument");
        }
        instruments.put(instrument.symbol(), instrument);
        changedInstruments.add(instrument.symbol());
    }

    public Long leverage(CoreLeverageKey key) {
        assertOwner();
        return onLane(key.userId(), lane -> lane.leverages.get(key));
    }

    void putLeverage(CoreLeverageKey key, long leveragePpm) {
        assertOwner();
        if (key == null || leveragePpm < 1_000_000L) {
            throw new IllegalArgumentException("invalid runtime leverage");
        }
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
        if (algoOrder == null) throw new IllegalArgumentException("invalid runtime algo order");
        onLane(algoOrder.userId(), lane -> lane.algoOrders.put(algoOrder.algoOrderId(), algoOrder));
        changedAlgoOrders.add(algoOrder.algoOrderId());
    }

    void removeAlgoOrder(long algoOrderId) {
        assertOwner();
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
        if (key == null || timer == null) throw new IllegalArgumentException("invalid runtime cancel-all-after timer");
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
        if (triggerOrder == null) throw new IllegalArgumentException("invalid runtime trigger order");
        onLane(triggerOrder.userId(), lane -> lane.triggerOrders.put(triggerOrder.triggerOrderId(), triggerOrder));
        changedTriggerOrders.add(triggerOrder.triggerOrderId());
    }

    void removeTriggerOrder(long triggerOrderId) {
        assertOwner();
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
        UserRuntime current = requireUser(userId);
        UserRuntime advanced = new UserRuntime(current.productLine(), userId,
                Math.incrementExact(current.revision()), current.positionMode());
        onLane(userId, lane -> lane.users.put(userId, advanced));
        publishedUsers.put(userId, advanced);
        if (laneCommandScope.get() == null) changedUsers.add(userId);
    }

    public void removeUser(long userId) {
        assertOwner();
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
        onLane(order.userId(), lane -> lane.orders.put(order.orderId(), order));
        publishedOrders.put(order.orderId(), order);
        orderLaneIds.put(order.orderId(), topology.accountLaneId(order.userId()) + 1L);
        changedOrders.add(order.orderId());
        changedUsers.add(order.userId());
    }

    public void putReservation(ReservationRuntime reservation) {
        assertOwner();
        ReservationRuntime previous = reservation(reservation.orderId());
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
        OrderRuntime previous = order(orderId);
        if (previous != null) {
            onLane(previous.userId(), lane -> lane.orders.remove(orderId));
            publishedOrders.remove(orderId);
            orderLaneIds.removeKey(orderId);
            changedOrders.add(orderId);
            changedUsers.add(previous.userId());
        }
    }

    public void replaceReservation(ReservationRuntime reservation) {
        assertOwner();
        ReservationRuntime previous = reservation(reservation.orderId());
        if (previous != null && previous.userId() != reservation.userId()) {
            throw new IllegalArgumentException("runtime reservation owner cannot change");
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
        if (laneCommandScope.get() == null) {
            changedReservations.add(reservation.orderId());
            changedUsers.add(reservation.userId());
            if (previous != null) changedUsers.add(previous.userId());
        }
    }

    public void removeReservation(long orderId, long userId) {
        assertOwner();
        onLane(userId, lane -> {
            ReservationRuntime current = lane.reservations.get(orderId);
            if (current == null || current.userId() != userId) {
                throw new IllegalArgumentException("runtime reservation is not registered: " + orderId);
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
        markPrices.put(markPrice.symbolId(), markPrice);
        changedMarkPrices.add(markPrice.symbolId());
    }

    public void putRiskSnapshot(long positionKey, RiskSnapshotRuntime snapshot) {
        assertOwner();
        onLane(snapshot.userId(), lane -> lane.riskSnapshots.put(positionKey, snapshot));
        changedRiskSnapshots.add(positionKey);
        changedUsers.add(snapshot.userId());
    }

    public void putRiskScan(RiskScanRuntime scan) {
        assertOwner();
        riskScans.put(scan.symbolId(), scan);
        changedRiskScans.add(scan.symbolId());
    }

    public void setNextLiquidationId(long nextLiquidationId) {
        assertOwner();
        if (nextLiquidationId <= 0) throw new IllegalArgumentException("invalid next liquidation id");
        this.nextLiquidationId = nextLiquidationId;
    }

    public void replaceLiquidation(LiquidationRuntime liquidation) {
        assertOwner();
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
        LiquidationRuntime previous = liquidation(liquidationId);
        if (previous != null) {
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
        markPrices.remove(symbolId);
        changedMarkPrices.add(symbolId);
    }

    public void removeRiskSnapshot(long positionKey) {
        assertOwner();
        RiskSnapshotRuntime previous = riskSnapshot(positionKey);
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            onLane(laneId, lane -> lane.riskSnapshots.remove(positionKey));
        }
        changedRiskSnapshots.add(positionKey);
        if (previous != null) changedUsers.add(previous.userId());
    }

    public void removeRiskScan(int symbolId) {
        assertOwner();
        riskScans.remove(symbolId);
        changedRiskScans.add(symbolId);
    }

    public void cancelOrder(long orderId, long userId, long releaseUnits) {
        assertOwner();
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
            balance.release(releaseUnits);
            OrderRuntime terminalOrder = order.withStatus(CoreOrderStatus.CANCELED,
                    Math.incrementExact(order.revision()));
            ReservationRuntime released = reservation.release(releaseUnits);
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
        OrderRuntime order = order(orderId);
        if (order == null) throw new IllegalArgumentException("runtime order is not terminal: " + orderId);
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
            balance.release(releaseUnits);
            ReservationRuntime released = reservation.release(releaseUnits);
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
            prunes.add(new TerminalOrderPrune(orderId, order.userId(),
                    identities.clientKey(order.userId(), order.clientOrderId())));
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

    public RuntimeMutationDelta captureMutationDelta() {
        assertOwner();
        RuntimeMutationDelta.CaptureRequest request = mutationCaptureRequest();
        return captureMutationDelta(request, captureLaneMutationValues(request));
    }

    public RuntimeMutationDelta captureMutationDelta(AccountLaneApplyResult laneApply) {
        assertOwner();
        if (laneApply == null) throw new IllegalArgumentException("lane apply is required");
        long requiredLaneMask = mutationLaneMask(laneApply.captureRequest);
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            if ((requiredLaneMask & 1L << laneId) != 0 && laneApply.laneValues[laneId] == null) {
                throw new IllegalStateException("lane apply does not contain the required mutation values");
            }
        }
        return captureMutationDelta(laneApply.captureRequest, laneApply.laneValues);
    }

    private RuntimeMutationDelta.CaptureRequest mutationCaptureRequest() {
        long[] userIds = changedUsers.toArray();
        TreeMap<Long, int[]> balanceAssetIds = new TreeMap<>();
        changedBalances.forEachKeyValue((userId, assetIds) ->
                balanceAssetIds.put(userId, assetIds.toArray()));
        TreeMap<Long, long[]> clientKeysByUser = new TreeMap<>();
        changedClientOrdersByUser.forEachKeyValue((userId, clientKeys) ->
                clientKeysByUser.put(userId, clientKeys.toArray()));
        long[] orderIds = changedOrders.toArray();
        long[] reservationIds = changedReservations.toArray();
        long[] positionKeys = changedPositions.toArray();
        long[] liquidationIds = changedLiquidations.toArray();
        long[] riskSnapshotKeys = changedRiskSnapshots.toArray();
        long[] algoOrderIds = changedAlgoOrders.toArray();
        long[] triggerOrderIds = changedTriggerOrders.toArray();
        return new RuntimeMutationDelta.CaptureRequest(
                userIds, Collections.unmodifiableMap(balanceAssetIds), orderIds, reservationIds, positionKeys,
                liquidationIds, riskSnapshotKeys, Collections.unmodifiableSet(new TreeSet<>(changedLeverages)),
                algoOrderIds, triggerOrderIds, Collections.unmodifiableMap(clientKeysByUser));
    }

    private RuntimeMutationDelta captureMutationDelta(RuntimeMutationDelta.CaptureRequest request,
                                                       RuntimeMutationDelta.LaneValues[] laneValues) {
        long[] userIds = request.userIds();
        Map<Long, int[]> balanceAssetIds = request.balanceAssetIds();
        Map<Long, long[]> clientKeysByUser = request.clientKeysByUser();
        long[] orderIds = request.orderIds();
        long[] reservationIds = request.reservationIds();
        long[] positionKeys = request.positionKeys();
        long[] liquidationIds = request.liquidationIds();
        long[] riskSnapshotKeys = request.riskSnapshotKeys();
        long[] algoOrderIds = request.algoOrderIds();
        long[] triggerOrderIds = request.triggerOrderIds();
        int pendingReservations = totalPendingReservations;
        TreeMap<Long, RuntimeMutationDelta.UserValue> users = new TreeMap<>();
        TreeMap<Long, OrderRuntime> orders = new TreeMap<>();
        TreeMap<Long, ReservationRuntime> reservations = new TreeMap<>();
        TreeSet<Long> pendingReservationIds = new TreeSet<>();
        TreeMap<Long, PositionRuntime> positions = new TreeMap<>();
        TreeMap<Long, LiquidationRuntime> liquidations = new TreeMap<>();
        TreeMap<Long, RiskSnapshotRuntime> riskSnapshots = new TreeMap<>();
        TreeMap<CoreLeverageKey, Long> leverages = new TreeMap<>();
        TreeMap<Long, CoreAlgoOrderState> algoOrders = new TreeMap<>();
        TreeMap<Long, CoreTriggerOrderState> triggerOrders = new TreeMap<>();
        TreeMap<RuntimeMutationDelta.RuntimeClientKey, Long> clientOrders = new TreeMap<>();
        for (RuntimeMutationDelta.LaneValues lane : laneValues) {
            if (lane == null) continue;
            users.putAll(lane.users());
            orders.putAll(lane.orders());
            reservations.putAll(lane.reservations());
            pendingReservationIds.addAll(lane.pendingReservations());
            positions.putAll(lane.positions());
            liquidations.putAll(lane.liquidations());
            riskSnapshots.putAll(lane.riskSnapshots());
            leverages.putAll(lane.leverages());
            algoOrders.putAll(lane.algoOrders());
            triggerOrders.putAll(lane.triggerOrders());
            clientOrders.putAll(lane.clientOrders());
        }

        TreeSet<Integer> changedMarkIds = intSet(changedMarkPrices.toArray());
        TreeMap<Integer, MarkPriceRuntime> currentMarks = new TreeMap<>();
        for (int symbolId : changedMarkIds) {
            MarkPriceRuntime mark = markPrices.get(symbolId);
            if (mark != null) currentMarks.put(symbolId, mark);
        }
        TreeSet<Integer> changedScanIds = intSet(changedRiskScans.toArray());
        TreeMap<Integer, RiskScanRuntime> currentScans = new TreeMap<>();
        for (int symbolId : changedScanIds) {
            RiskScanRuntime scan = riskScans.get(symbolId);
            if (scan != null) currentScans.put(symbolId, scan);
        }
        TreeMap<String, CoreInstrumentState> currentInstruments = new TreeMap<>();
        for (String symbol : changedInstruments) {
            CoreInstrumentState instrument = instruments.get(symbol);
            if (instrument != null) currentInstruments.put(symbol, instrument);
        }
        TreeMap<CoreCancelAllAfterKey, CoreCancelAllAfterState> currentTimers = new TreeMap<>();
        for (CoreCancelAllAfterKey key : changedCancelAllAfterTimers) {
            CoreCancelAllAfterState timer = cancelAllAfterTimers.get(key);
            if (timer != null) currentTimers.put(key, timer);
        }

        return new RuntimeMutationDelta(productLine, revision, pendingReservations,
                RuntimeMutationDelta.ValueChanges.of(longSet(userIds), users),
                RuntimeMutationDelta.ValueChanges.of(longSet(orderIds), orders),
                RuntimeMutationDelta.ValueChanges.of(longSet(reservationIds), reservations),
                pendingReservationIds,
                RuntimeMutationDelta.ValueChanges.of(longSet(positionKeys), positions),
                RuntimeMutationDelta.ValueChanges.of(longSet(liquidationIds), liquidations),
                RuntimeMutationDelta.ValueChanges.of(longSet(riskSnapshotKeys), riskSnapshots),
                RuntimeMutationDelta.ValueChanges.of(new TreeSet<>(changedLeverages), leverages),
                RuntimeMutationDelta.ValueChanges.of(longSet(algoOrderIds), algoOrders),
                RuntimeMutationDelta.ValueChanges.of(longSet(triggerOrderIds), triggerOrders),
                RuntimeMutationDelta.ValueChanges.of(runtimeClientKeys(clientKeysByUser), clientOrders),
                RuntimeMutationDelta.ValueChanges.of(changedMarkIds, currentMarks),
                RuntimeMutationDelta.ValueChanges.of(changedScanIds, currentScans),
                RuntimeMutationDelta.ValueChanges.of(new TreeSet<>(changedInstruments), currentInstruments),
                RuntimeMutationDelta.ValueChanges.of(new TreeSet<>(changedCancelAllAfterTimers), currentTimers),
                captureTreasuryMutation(), nextLiquidationId, riskScanControl);
    }

    private RuntimeMutationDelta.LaneValues[] captureLaneMutationValues(
            RuntimeMutationDelta.CaptureRequest request) {
        RuntimeMutationDelta.LaneValues[] values = new RuntimeMutationDelta.LaneValues[accountLanes.length];
        long requiredLaneMask = mutationLaneMask(request);
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            if ((requiredLaneMask & 1L << laneId) == 0) {
                values[laneId] = RuntimeMutationDelta.emptyLaneValues();
                continue;
            }
            values[laneId] = RuntimeMutationDelta.captureLane(accountLanes[laneId], request);
        }
        return values;
    }

    private long mutationLaneMask(RuntimeMutationDelta.CaptureRequest request) {
        long mask = 0;
        for (long userId : request.userIds()) mask |= 1L << topology.accountLaneId(userId);
        for (long userId : request.balanceAssetIds().keySet()) mask |= 1L << topology.accountLaneId(userId);
        for (CoreLeverageKey key : request.leverageKeys()) mask |= 1L << topology.accountLaneId(key.userId());
        for (long userId : request.clientKeysByUser().keySet()) mask |= 1L << topology.accountLaneId(userId);
        boolean laneStateChanged = request.orderIds().length != 0 || request.reservationIds().length != 0
                || request.positionKeys().length != 0 || request.liquidationIds().length != 0
                || request.riskSnapshotKeys().length != 0 || request.algoOrderIds().length != 0
                || request.triggerOrderIds().length != 0;
        if (mask == 0 && laneStateChanged) {
            return accountLanes.length == Long.SIZE ? -1L : (1L << accountLanes.length) - 1;
        }
        return mask;
    }

    private RuntimeMutationDelta.TreasuryValues captureTreasuryMutation() {
        TreeSet<Integer> assets = intSet(treasury.changedAssets().toArray());
        TreeMap<Integer, RuntimeMutationDelta.AssetLedger> assetValues = new TreeMap<>();
        for (int assetId : assets) {
            assetValues.put(assetId, new RuntimeMutationDelta.AssetLedger(treasury.fee(assetId),
                    treasury.insurance(assetId), treasury.insuranceDeficit(assetId),
                    treasury.liquidationFee(assetId), treasury.fundingResidual(assetId),
                    treasury.roundingResidual(assetId), treasury.clearingPnl(assetId)));
        }
        TreeSet<Integer> fundingSymbols = intSet(treasury.changedFundingSymbols().toArray());
        TreeMap<Integer, RuntimeMutationDelta.FundingLedger> funding = new TreeMap<>();
        for (int symbolId : fundingSymbols) {
            funding.put(symbolId, new RuntimeMutationDelta.FundingLedger(
                    treasury.fundingSettlement(symbolId), treasury.fundingProgress(symbolId)));
        }
        TreeSet<Integer> lifecycleSymbols = intSet(treasury.changedLifecycleSymbols().toArray());
        TreeMap<Integer, RuntimeMutationDelta.LifecycleLedger> lifecycle = new TreeMap<>();
        for (int symbolId : lifecycleSymbols) {
            lifecycle.put(symbolId, new RuntimeMutationDelta.LifecycleLedger(
                    treasury.lifecycleSettlement(symbolId), treasury.lifecycleProgress(symbolId)));
        }
        return new RuntimeMutationDelta.TreasuryValues(
                RuntimeMutationDelta.ValueChanges.of(assets, assetValues),
                RuntimeMutationDelta.ValueChanges.of(fundingSymbols, funding),
                RuntimeMutationDelta.ValueChanges.of(lifecycleSymbols, lifecycle));
    }

    private static TreeSet<Long> longSet(long[] values) {
        TreeSet<Long> result = new TreeSet<>();
        for (long value : values) result.add(value);
        return result;
    }

    private static TreeSet<Integer> intSet(int[] values) {
        TreeSet<Integer> result = new TreeSet<>();
        for (int value : values) result.add(value);
        return result;
    }

    private static TreeSet<RuntimeMutationDelta.RuntimeClientKey> runtimeClientKeys(
            Map<Long, long[]> keysByUser) {
        TreeSet<RuntimeMutationDelta.RuntimeClientKey> result = new TreeSet<>();
        keysByUser.forEach((userId, keys) -> {
            for (long key : keys) result.add(new RuntimeMutationDelta.RuntimeClientKey(userId, key));
        });
        return result;
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

}
