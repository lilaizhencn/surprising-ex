package com.surprising.aeron.service.state;
import com.surprising.aeron.service.lane.SettlementLaneWorker;
import com.surprising.aeron.service.state.model.CoreOrderStatus;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Immutable matcher fact routed once to every Account Lane it touches.
 * Mutable completion state is isolated from the fact payload and is only used by the owner coordinator.
 */
public final class MatcherSettlementEvent implements SettlementLaneWorker.Command {
    private static final RuntimeTreasuryDelta EMPTY_TREASURY_DELTA = new RuntimeTreasuryDelta(1);
    private static final int CACHE_LINE_LONGS = 16;
    private static final VarHandle LONGS = MethodHandles.arrayElementVarHandle(long[].class);

    private long commitSequence;
    private long requiredLaneMask;
    private long commitTimestamp;
    private long commitClusterPosition;
    private MatcherSettlementPlan plan;
    private MatcherSettlementPlan[] batchPlans;
    private TradingRuntimeState runtime;
    private RuntimeIdentityRegistry identities;
    private CoreInstrumentState instrument;
    private CoreInstrumentState[] batchInstruments;
    private int baseAssetId;
    private int quoteAssetId;
    private int settleAssetId;
    private int[] batchBaseAssetIds;
    private int[] batchQuoteAssetIds;
    private int[] batchSettleAssetIds;
    private RuntimeTreasuryDelta[] touchedLaneTreasuryDeltas;
    private TradingRuntimeState.MatcherSettlementChanges changes;
    private boolean isolatedChanges;
    private boolean treasuryTrades;
    private RuntimeFundsDelta collectedFundsDelta = RuntimeFundsDelta.empty();
    private long[] completedLanes;
    private boolean collected;
    /** Owner 已以 acquire 观察到的完成位；本代事件不会倒退，复用时清零。 */
    private long observedCompletedLaneMask;

    // Storage belongs to this pooled event, never to a shared owner scratch buffer.
    private BatchStorage batchStorage;
    /** 本代有效项数；缓冲容量不决定业务执行范围。 */
    private int batchPlanCount;
    private boolean direct;
    /** Matcher proof sequence for direct events; may differ from the final Lane commit sequence. */
    private long directCoreSequence;
    private boolean dispatched; // Owner only.
    private volatile boolean directPublished;
    private Throwable directFailure;
    private long routedLaneMask;
    private java.util.UUID directCommandId;
    private int directShard;
    private java.util.List<Long> authorizedCancellations = java.util.List.of();
    private com.surprising.aeron.service.matching.CoreMatchingResult firstDirectResult, lastDirectResult;
    /** 完成前由命令持有，不归还结果容器；事件回收时释放引用。 */
    LaneOrderResultTarget resultTarget;
    static final class BatchStorage {
        /** 本批币对ID到首个元数据槽；只在Owner构建阶段写入，事件回收时清空。 */
        final org.eclipse.collections.impl.map.mutable.primitive.IntIntHashMap metadataSlots =
                new org.eclipse.collections.impl.map.mutable.primitive.IntIntHashMap();
        final MatcherSettlementPlan[] plans;
        final CoreInstrumentState[] instruments;
        final OrderRuntime[] admittedOrders;
        final int[] baseAssetIds, quoteAssetIds, settleAssetIds;
        BatchStorage(int size) {
            plans = new MatcherSettlementPlan[size];
            for (int i = 0; i < size; i++) plans[i] = new MatcherSettlementPlan();
            instruments = new CoreInstrumentState[size];
            admittedOrders = new OrderRuntime[size];
            baseAssetIds = new int[size]; quoteAssetIds = new int[size]; settleAssetIds = new int[size];
        }
        void clear() {
            metadataSlots.clear();
            for (MatcherSettlementPlan plan : plans) plan.clearReferences();
            java.util.Arrays.fill(instruments, null);
            java.util.Arrays.fill(admittedOrders, null);
        }
    }
    BatchStorage batchStorage(int size) {
        if (plan != null) throw new IllegalStateException("settlement event is still active");
        if (size <= 0) throw new IllegalArgumentException("settlement batch must not be empty");
        if (batchStorage == null || batchStorage.plans.length < size)
            batchStorage = new BatchStorage(size);
        return batchStorage;
    }
    void discardBatchStorage() {
        if (plan != null) throw new IllegalStateException("cannot discard active settlement");
        if (batchStorage != null) batchStorage.clear();
    }

    MatcherSettlementEvent() {
    }

    /** Owner reserves only the existing ordering slot and stable input metadata. */
    void prepareDirect(long sequence, long laneMask, long timestamp, long position,
                       java.util.UUID commandId, int shard, TradingRuntimeState runtime,
                       RuntimeIdentityRegistry identities, int count, java.util.List<Long> cancellations,
                       LaneOrderResultTarget target) {
        prepareDirect(sequence, sequence, laneMask, timestamp, position, commandId, shard,
                runtime, identities, count, cancellations, target);
    }

    /**
     * Reserves a direct event with an explicit final Lane commit sequence.  Sequential batch
     * items use commitSequence=0: their facts are applied and collected immediately, while the
     * batch's terminal Lane mutation advances the account sequence once for the whole command.
     */
    void prepareDirect(long coreSequence, long commitSequence, long laneMask, long timestamp, long position,
                       java.util.UUID commandId, int shard, TradingRuntimeState runtime,
                       RuntimeIdentityRegistry identities, int count, java.util.List<Long> cancellations,
                       LaneOrderResultTarget target) {
        if (coreSequence <= 0 || commitSequence < 0 || laneMask == 0 || timestamp < 0 || position < 0 || commandId == null
                || count <= 0 || batchStorage == null || count > batchStorage.plans.length)
            throw new IllegalArgumentException("invalid direct settlement reservation");
        direct = true; directPublished = false; dispatched = false; directFailure = null;
        directCoreSequence = coreSequence;
        this.commitSequence = commitSequence; routedLaneMask = requiredLaneMask = laneMask;
        commitTimestamp = timestamp; commitClusterPosition = position;
        directCommandId = commandId; directShard = shard;
        authorizedCancellations = java.util.Objects.requireNonNull(cancellations);
        this.runtime = runtime; this.identities = identities; resultTarget = target;
        batchPlanCount = count; batchPlans = batchStorage.plans; plan = batchPlans[0];
        batchInstruments = batchStorage.instruments;
        batchBaseAssetIds = batchStorage.baseAssetIds; batchQuoteAssetIds = batchStorage.quoteAssetIds;
        batchSettleAssetIds = batchStorage.settleAssetIds;
        instrument = batchInstruments[0]; baseAssetId = batchBaseAssetIds[0];
        quoteAssetId = batchQuoteAssetIds[0]; settleAssetId = batchSettleAssetIds[0];
        changes = runtime.acquireMatcherSettlementChanges(laneMask);
        changes.directPositionIdentities = true;
        isolatedChanges = true; collected = false;
        collectedFundsDelta = RuntimeFundsDelta.empty();
        prepareTreasuryDeltas(runtime.topology().accountLaneCount(), true);
        resetCompletions(runtime.topology().accountLaneCount());
        runtime.expectMatcherSettlement(laneMask);
    }

    public boolean direct() { return direct; }
    TradingRuntimeState runtime() { return runtime; }
    public boolean dispatched() { return dispatched; }
    public void commitFence(long timestamp, long position) {
        if (!direct || dispatched || timestamp < 0 || position < 0)
            throw new IllegalStateException("invalid direct settlement commit fence");
        commitTimestamp = timestamp;
        commitClusterPosition = position;
    }
    void markDispatched() {
        if (!direct || dispatched) throw new IllegalStateException("direct settlement dispatched twice");
        dispatched = true;
    }
    long routedLaneMask() { return direct ? routedLaneMask : requiredLaneMask; }
    public boolean ready() { return !direct || directPublished; }

    /** Matcher publishes into the already-owned event; Owner does not build or copy its result. */
    public void publishDirectResult(com.surprising.aeron.service.matching.CoreMatchingResult result) {
        if (batchPlanCount != 1) throw new IllegalStateException("single result for a settlement batch");
        try {
            buildDirectItem(0, result, null);
            firstDirectResult = lastDirectResult = result;
            finishDirectPublication();
        } catch (Throwable failure) { failDirect(failure); throw failure; }
    }

    public void publishDirectResults(java.util.List<com.surprising.aeron.service.matching.CoreMatchingResult> results) {
        try {
            if (results == null || results.size() != batchPlanCount)
                throw new IllegalStateException("direct matcher batch is incomplete");
            com.surprising.aeron.service.matching.CoreMatchingResult previous = null;
            for (int index = 0; index < batchPlanCount; index++) {
                var result = results.get(index);
                buildDirectItem(index, result, previous);
                previous = result;
            }
            firstDirectResult = results.getFirst(); lastDirectResult = previous;
            finishDirectPublication();
        } catch (Throwable failure) { failDirect(failure); throw failure; }
    }

    private void buildDirectItem(int index, com.surprising.aeron.service.matching.CoreMatchingResult result,
                                 com.surprising.aeron.service.matching.CoreMatchingResult previous) {
        if (!direct || directPublished || result == null) throw new IllegalStateException("invalid direct publication");
        var nativeCommand = result.nativeCommand();
        var prefix = result.matcherPrefix();
        if (nativeCommand.coreSequence() != directCoreSequence || !nativeCommand.matches(directCommandId)
                || nativeCommand.matcherShardId() != directShard || !prefix.bound() || prefix.after() == prefix.before()
                || previous != null && (prefix.before() != previous.matcherPrefix().after()
                || nativeCommand.matcherSequence() <= previous.nativeCommand().matcherSequence())
                || result.outcome() == com.surprising.aeron.service.matching.CoreMatchingResult.Outcome.FATAL_DIVERGENCE)
            throw new IllegalStateException("direct matcher result proof is inconsistent");
        if (result.outcome() == com.surprising.aeron.service.matching.CoreMatchingResult.Outcome.KNOWN_PREFIX_APPLIED) {
            boolean containsTrade = false;
            for (var event : result.matcherEvents()) {
                if (event.eventType() == exchange.core2.core.common.MatcherEventType.TRADE) {
                    containsTrade = true;
                    break;
                }
            }
            if (authorizedCancellations.isEmpty() || containsTrade)
                throw new IllegalStateException("direct matcher returned an unreconciled partial outcome");
        }
        batchPlans[index].buildDirect(directCoreSequence, batchStorage.admittedOrders[index],
                batchInstruments[index], result, runtime);
        batchPlans[index].preCancellationsFromResult(result.cancellations(), authorizedCancellations);
    }

    private void finishDirectPublication() {
        long actualLanes = 0; int orders = 0;
        for (int index = 0; index < batchPlanCount; index++) {
            actualLanes |= batchPlans[index].requiredLaneMask();
            orders = Math.addExact(orders, batchPlans[index].orderCount());
        }
        if ((actualLanes & ~routedLaneMask) != 0)
            throw new IllegalStateException("matcher result escaped its admitted Lane dependency scope");
        requiredLaneMask = actualLanes;
        treasuryTrades = hasTrade(batchPlans, batchPlanCount);
        changes.ensureOrderCapacity(orders, actualLanes);
        directPublished = true;
        runtime.signalDirectSettlement(routedLaneMask);
    }

    public void failDirect(Throwable failure) {
        if (!direct || directPublished) return;
        directFailure = java.util.Objects.requireNonNull(failure);
        directPublished = true;
        runtime.signalDirectSettlement(routedLaneMask);
    }

    public com.surprising.aeron.service.matching.CoreMatchingResult firstDirectResult() {
        requireDirectResult(); return firstDirectResult;
    }
    public com.surprising.aeron.service.matching.CoreMatchingResult lastDirectResult() {
        requireDirectResult(); return lastDirectResult;
    }
    private void requireDirectResult() {
        if (!direct || !directPublished) throw new IllegalStateException("direct result is not published");
        if (directFailure != null) throw new IllegalStateException("direct matcher failed", directFailure);
    }

    MatcherSettlementEvent prepare(long commitSequence, long requiredLaneMask,
                                   long commitTimestamp, long commitClusterPosition,
                                   MatcherSettlementPlan plan, TradingRuntimeState runtime,
                                   RuntimeIdentityRegistry identities, CoreInstrumentState instrument,
                                   int baseAssetId, int quoteAssetId, int settleAssetId, int laneCount,
                                   boolean captureIsolatedChanges) {
        if (commitSequence < 0 || requiredLaneMask == 0
                || (commitSequence == 0 && (commitTimestamp != -1 || commitClusterPosition != -1))
                || (commitSequence != 0 && (commitTimestamp < 0 || commitClusterPosition < 0))
                || plan == null || runtime == null
                || identities == null || instrument == null || laneCount <= 0) {
            throw new IllegalArgumentException("invalid immutable matcher settlement event");
        }
        this.commitSequence = commitSequence;
        this.requiredLaneMask = requiredLaneMask;
        this.commitTimestamp = commitTimestamp;
        this.commitClusterPosition = commitClusterPosition;
        this.plan = plan;
        this.runtime = runtime;
        this.identities = identities;
        this.instrument = instrument;
        this.baseAssetId = baseAssetId;
        this.quoteAssetId = quoteAssetId;
        this.settleAssetId = settleAssetId;
        this.isolatedChanges = captureIsolatedChanges;
        this.treasuryTrades = plan.tradeCount() != 0;
        this.changes = commitSequence != 0 || captureIsolatedChanges
                ? runtime.acquireMatcherSettlementChanges(requiredLaneMask) : null;
        if (changes != null) {
            changes.ensureOrderCapacity(Math.addExact(plan.orderCount(), plan.preCancellationCount()),
                    requiredLaneMask);
        }
        if (plan.tradeCount() == 0) {
            if (touchedLaneTreasuryDeltas != null) {
                for (RuntimeTreasuryDelta delta : touchedLaneTreasuryDeltas) delta.clear();
            }
        } else {
            if (touchedLaneTreasuryDeltas == null || touchedLaneTreasuryDeltas.length != laneCount) {
                touchedLaneTreasuryDeltas = new RuntimeTreasuryDelta[laneCount];
                for (int index = 0; index < laneCount; index++) {
                    touchedLaneTreasuryDeltas[index] = new RuntimeTreasuryDelta();
                }
            } else {
                for (RuntimeTreasuryDelta delta : touchedLaneTreasuryDeltas) delta.clear();
            }
        }
        collectedFundsDelta = RuntimeFundsDelta.empty();
        collected = false;
        resetCompletions(laneCount);
        runtime.expectMatcherSettlement(requiredLaneMask);
        return this;
    }

    MatcherSettlementEvent prepareBatch(
            long commitSequence, long requiredLaneMask,
            long commitTimestamp, long commitClusterPosition,
            MatcherSettlementPlan[] plans, int planCount, TradingRuntimeState runtime,
            RuntimeIdentityRegistry identities, CoreInstrumentState[] instruments,
            int[] baseAssetIds, int[] quoteAssetIds, int[] settleAssetIds, int laneCount) {
        if (commitSequence <= 0 || commitTimestamp < 0 || commitClusterPosition < 0
                || requiredLaneMask == 0 || plans == null || planCount <= 0 || planCount > plans.length || runtime == null
                || identities == null || instruments == null || instruments.length != plans.length
                || baseAssetIds == null || baseAssetIds.length != plans.length
                || quoteAssetIds == null || quoteAssetIds.length != plans.length
                || settleAssetIds == null || settleAssetIds.length != plans.length || laneCount <= 0) {
            throw new IllegalArgumentException("invalid matcher settlement batch event");
        }
        long coreSequence = plans[0].coreSequence();
        int expectedOrders = 0;
        for (int index = 0; index < planCount; index++) {
            MatcherSettlementPlan value = plans[index];
            if (value == null || value.coreSequence() != coreSequence
                    || (value.requiredLaneMask() & ~requiredLaneMask) != 0) {
                throw new IllegalArgumentException("invalid matcher settlement batch plan");
            }
            expectedOrders = Math.addExact(expectedOrders,
                    Math.addExact(value.orderCount(), value.preCancellationCount()));
        }
        this.commitSequence = commitSequence;
        this.requiredLaneMask = requiredLaneMask;
        this.commitTimestamp = commitTimestamp;
        this.commitClusterPosition = commitClusterPosition;
        this.plan = plans[0];
        this.batchPlans = plans;
        this.batchPlanCount = planCount;
        this.runtime = runtime;
        this.identities = identities;
        this.instrument = instruments[0];
        this.batchInstruments = instruments;
        this.baseAssetId = baseAssetIds[0];
        this.quoteAssetId = quoteAssetIds[0];
        this.settleAssetId = settleAssetIds[0];
        this.batchBaseAssetIds = baseAssetIds;
        this.batchQuoteAssetIds = quoteAssetIds;
        this.batchSettleAssetIds = settleAssetIds;
        this.isolatedChanges = true;
        this.changes = runtime.acquireMatcherSettlementChanges(requiredLaneMask);
        changes.ensureOrderCapacity(expectedOrders, requiredLaneMask);
        treasuryTrades = hasTrade(plans, planCount);
        prepareTreasuryDeltas(laneCount, treasuryTrades);
        collectedFundsDelta = RuntimeFundsDelta.empty();
        collected = false;
        resetCompletions(laneCount);
        runtime.expectMatcherSettlement(requiredLaneMask);
        return this;
    }

    void clear() {
        if (!complete() || changes != null) {
            throw new IllegalStateException("cannot recycle an incomplete matcher settlement");
        }
        direct = false; directPublished = false; directFailure = null; dispatched = false;
        directCoreSequence = 0;
        directCommandId = null; authorizedCancellations = java.util.List.of();
        firstDirectResult = lastDirectResult = null; routedLaneMask = 0;
        resultTarget = null;
        plan = null;
        if (batchStorage != null) batchStorage.clear();
        batchPlans = null;
        batchPlanCount = 0;
        runtime = null;
        identities = null;
        instrument = null;
        batchInstruments = null;
        batchBaseAssetIds = null;
        batchQuoteAssetIds = null;
        batchSettleAssetIds = null;
        collectedFundsDelta = RuntimeFundsDelta.empty();
        isolatedChanges = false;
        treasuryTrades = false;
        collected = false;
    }

    @Override
    public void execute(AccountLaneState lane) {
        if (direct) requireDirectResult();
        long startedNanos = System.nanoTime();
        int laneId = lane.laneId();
        long laneMask = 1L << laneId;
        if ((requiredLaneMask & laneMask) == 0) {
            if (direct && (routedLaneMask & laneMask) != 0) {
                publishCompletion(lane, startedNanos);
                return;
            }
            throw new IllegalStateException("matcher fact was routed to an unrelated account lane");
        }
        if (changes == null) runtime.enterLaneCommandScope(lane);
        else runtime.enterMatcherSettlementScope(lane, changes);
        try {
            RuntimeTreasuryDelta delta = !treasuryTrades
                    ? EMPTY_TREASURY_DELTA : touchedLaneTreasuryDeltas[laneSlot(laneId)];
            if (batchPlans == null) {
                applyPlan(lane, laneId, plan, instrument,
                        baseAssetId, quoteAssetId, settleAssetId, delta);
                if (commitSequence != 0) {
                    runtime.stampMatcherOrders(lane, plan, commitTimestamp, commitClusterPosition);
                }
            }
            else for (int index = 0; index < batchPlanCount; index++) {
                MatcherSettlementPlan batchPlan = batchPlans[index];
                if ((batchPlan.requiredLaneMask() & laneMask) == 0) continue;
                applyPlan(lane, laneId, batchPlan, batchInstruments[index],
                        batchBaseAssetIds[index], batchQuoteAssetIds[index],
                        batchSettleAssetIds[index], delta);
                if (commitSequence != 0) {
                    runtime.stampMatcherOrders(
                            lane, batchPlan, commitTimestamp, commitClusterPosition);
                }
            }
            if (changes != null) {
                changes.prepareLaneTerminal(laneId, identities, lane, runtime);
            }
            if (commitSequence != 0) {
                if (resultTarget != null && changes != null
                        && laneId == runtime.topology().accountLaneId(plan.activeUserId()))
                    LaneOrderResultTarget.capture(resultTarget, changes.laneDeltas[laneId], identities, lane);
                lane.applied(commitSequence);
                lane.committed(commitSequence);
                runtime.publishLaneHashes(lane);
            }
        } finally {
            if (changes == null) runtime.exitLaneCommandScope(lane);
            else runtime.exitMatcherSettlementScope(lane, changes);
        }
        // Publish each Lane independently. The owner validates the event-wide completion fence,
        // avoiding a contended atomic OR written by every Lane.
        publishCompletion(lane, startedNanos);
    }

    private void publishCompletion(AccountLaneState lane, long startedNanos) {
        int laneId = lane.laneId();
        TradingRuntimeState completionRuntime = runtime;
        long completionSequence = plan.coreSequence();
        completionRuntime.recordMatcherLaneOperation(lane, System.nanoTime() - startedNanos);
        int completionOffset = laneId * CACHE_LINE_LONGS;
        if ((long) LONGS.getAcquire(completedLanes, completionOffset) != 0) {
            throw new IllegalStateException("account lane completed the same matcher fact twice");
        }
        LONGS.setRelease(completedLanes, completionOffset, 1L);
        completionRuntime.publishMatcherSettlementReady(laneId, completionSequence);
    }

    private void applyPlan(AccountLaneState lane, int laneId, MatcherSettlementPlan value,
                           CoreInstrumentState valueInstrument, int valueBaseAssetId,
                           int valueQuoteAssetId, int valueSettleAssetId, RuntimeTreasuryDelta delta) {
        value.validateDirectLane(lane, runtime, identities, valueInstrument);
        for (int index = 0; index < value.preCancellationCount(); index++) {
            long orderId = value.preCancellationOrderId(index);
            OrderRuntime order = lane.orders.get(orderId);
            if (order != null && order.status() == CoreOrderStatus.OPEN) {
                runtime.cancelOrderInLane(order.userId(), orderId);
            }
        }
        if (value.rejectedTaker()) {
            if (runtime.currentLaneOwns(value.activeUserId()))
                runtime.rejectOrderInLane(value.activeUserId(), value.takerOrderId(), commitTimestamp, commitClusterPosition);
        } else if (runtime.productLine().isDerivative()) {
            RuntimeDerivativeMatchProcessor.applyLane(value.takerOrderId(), value, laneId,
                    runtime, identities, valueInstrument, valueSettleAssetId, delta, commitTimestamp, commitClusterPosition);
        } else {
            RuntimeSpotMatchProcessor.applyLane(value.takerOrderId(), value, laneId,
                    runtime, valueInstrument, valueBaseAssetId, valueQuoteAssetId, delta, commitTimestamp, commitClusterPosition);
        }
        runtime.completeMatcherPendingReservations(lane, value);
        var trigger = value.completedTrigger();
        if (trigger != null && runtime.currentLaneOwns(trigger.userId())) runtime.putTriggerOrder(trigger);
    }

    private void prepareTreasuryDeltas(int laneCount, boolean trades) {
        if (!trades) {
            if (touchedLaneTreasuryDeltas != null) {
                for (RuntimeTreasuryDelta delta : touchedLaneTreasuryDeltas) delta.clear();
            }
            return;
        }
        if (touchedLaneTreasuryDeltas == null || touchedLaneTreasuryDeltas.length != laneCount) {
            touchedLaneTreasuryDeltas = new RuntimeTreasuryDelta[laneCount];
            for (int index = 0; index < laneCount; index++) {
                touchedLaneTreasuryDeltas[index] = new RuntimeTreasuryDelta();
            }
        } else {
            for (RuntimeTreasuryDelta delta : touchedLaneTreasuryDeltas) delta.clear();
        }
    }

    private static boolean hasTrade(MatcherSettlementPlan[] plans, int planCount) {
        for (int index = 0; index < planCount; index++) if (plans[index].tradeCount() != 0) return true;
        return false;
    }

    public long commitSequence() { return commitSequence; }
    public long requiredLaneMask() { return requiredLaneMask; }
    public long completedLaneMask() {
        if (!ready()) return 0;
        long remaining = routedLaneMask() & ~observedCompletedLaneMask;
        while (remaining != 0) {
            int laneId = Long.numberOfTrailingZeros(remaining);
            long bit = 1L << laneId;
            remaining &= ~bit;
            if ((long) LONGS.getAcquire(completedLanes, laneId * CACHE_LINE_LONGS) != 0)
                observedCompletedLaneMask |= bit;
        }
        return observedCompletedLaneMask;
    }

    public boolean complete() { return ready() && completedLaneMask() == routedLaneMask(); }
    public MatcherSettlementPlan plan() { return plan; }
    int planCount() { return batchPlans == null ? 1 : batchPlanCount; }
    MatcherSettlementPlan plan(int index) {
        if (index < 0 || index >= planCount()) throw new IndexOutOfBoundsException(index);
        return batchPlans == null ? plan : batchPlans[index];
    }
    RuntimeIdentityRegistry identities() { return identities; }
    boolean hasIsolatedChanges() { return isolatedChanges; }
    CoreInstrumentState instrument() { return instrument; }
    int baseAssetId() { return baseAssetId; }
    int quoteAssetId() { return quoteAssetId; }
    int settleAssetId() { return settleAssetId; }
    TradingRuntimeState.MatcherSettlementChanges changes() {
        if (changes == null) throw new IllegalStateException("matcher settlement changes are unavailable");
        return changes;
    }
    TradingRuntimeState.MatcherSettlementChanges takeChanges() {
        TradingRuntimeState.MatcherSettlementChanges value = changes();
        changes = null;
        return value;
    }
    public RuntimeFundsDelta collectedFundsDelta() { return collectedFundsDelta; }
    void collectedFundsDelta(RuntimeFundsDelta value) {
        collectedFundsDelta = value == null ? RuntimeFundsDelta.empty() : value;
    }

    RuntimeTreasuryDelta collectTreasuryDelta() {
        if (!complete()) return null;
        if (collected) throw new IllegalStateException("matcher settlement event was already collected");
        collected = true;
        if (!treasuryTrades) return EMPTY_TREASURY_DELTA;
        RuntimeTreasuryDelta aggregate = null;
        for (RuntimeTreasuryDelta delta : touchedLaneTreasuryDeltas) {
            if (aggregate == null) aggregate = delta;
            else aggregate.merge(delta);
        }
        if (aggregate == null) throw new IllegalStateException("matcher settlement event has no lane delta");
        return aggregate;
    }

    private int laneSlot(int laneId) {
        long laneBit = 1L << laneId;
        if ((requiredLaneMask & laneBit) == 0) {
            throw new IllegalStateException("matcher fact has no treasury slot for lane " + laneId);
        }
        return Long.bitCount(requiredLaneMask & (laneBit - 1));
    }

    private void resetCompletions(int laneCount) {
        observedCompletedLaneMask = 0;
        int length = Math.multiplyExact(laneCount, CACHE_LINE_LONGS);
        if (completedLanes == null || completedLanes.length != length) completedLanes = new long[length];
        else for (int laneId = 0; laneId < laneCount; laneId++) {
            LONGS.setRelease(completedLanes, laneId * CACHE_LINE_LONGS, 0L);
        }
    }
}
