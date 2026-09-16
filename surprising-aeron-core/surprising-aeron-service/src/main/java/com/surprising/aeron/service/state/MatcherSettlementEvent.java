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
    /** Diagnostic only: no clocks or additional objects on the default trading path. */
    public static final boolean LATENCY_DIAGNOSTICS = Boolean.getBoolean("core.settlementLatencyDiagnostics");
    private long matcherPublishedNanos;

    public long matcherPublishedNanos() { return matcherPublishedNanos; }
    // Read only after complete(): the Lane completion acquire protects these padding slots.
    public long laneStartedNanos(int laneId) { return completedLanes[laneId * CACHE_LINE_LONGS + 1]; }
    public long laneFinishedNanos(int laneId) { return completedLanes[laneId * CACHE_LINE_LONGS + 2]; }

    public interface MatcherCompletionRoute {
        void releaseMatcherSubmission(int matcherShard);
    }

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
    private boolean cancellation;
    private long cancellationUserId;
    private com.surprising.aeron.service.command.order.ResolvedMatchingAdmission replacement;
    private int replacementAssetId;
    private long replacementIdentityAllocations;
    private com.surprising.aeron.service.state.model.CoreTriggerOrderState sourceTrigger;
    private long triggeredAt;
    /** Matcher proof sequence for direct events; may differ from the final Lane commit sequence. */
    private long directCoreSequence;
    private boolean dispatched; // Owner only.
    private volatile boolean directPublished;
    /** Matcher publication keeps the pooled event alive until its SPSC slot is released. */
    private boolean matcherPublicationDeferred;
    private Throwable directFailure;
    private long routedLaneMask;
    private java.util.UUID directCommandId;
    private int directShard;
    private java.util.List<Long> authorizedCancellations = java.util.List.of();
    private com.surprising.aeron.service.matching.CoreMatchingResult firstDirectResult, lastDirectResult;
    /** 已有序号上下文的路由；直达结果先释放路由，再通知 Lane。 */
    private MatcherCompletionRoute matcherCompletionRoute;
    private int matcherCompletionShard;
    /** Optional normal-PLACE admission that is queued ahead of this direct settlement. */
    private PlaceAdmissionEvent admissionDependency;
    /** Admission outcome copied before the pooled admission event is recycled by the Owner. */
    private volatile boolean admissionResolved;
    private RuntimeException admissionRejection;
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
        /** Number of slots populated by the current preparation, not backing capacity. */
        private int activeSize;
        BatchStorage(int size) {
            plans = new MatcherSettlementPlan[size];
            for (int i = 0; i < size; i++) plans[i] = new MatcherSettlementPlan();
            instruments = new CoreInstrumentState[size];
            admittedOrders = new OrderRuntime[size];
            baseAssetIds = new int[size]; quoteAssetIds = new int[size]; settleAssetIds = new int[size];
        }
        void activate(int size) {
            if (size <= 0 || size > plans.length) throw new IllegalArgumentException("invalid active batch size");
            activeSize = size;
        }
        void clear() {
            metadataSlots.clear();
            for (int index = 0; index < activeSize; index++) {
                plans[index].clearReferences();
                instruments[index] = null;
                admittedOrders[index] = null;
            }
            activeSize = 0;
        }
    }
    BatchStorage batchStorage(int size) {
        if (plan != null) throw new IllegalStateException("settlement event is still active");
        if (size <= 0) throw new IllegalArgumentException("settlement batch must not be empty");
        if (batchStorage == null || batchStorage.plans.length < size)
            batchStorage = new BatchStorage(size);
        batchStorage.activate(size);
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
        direct = true; cancellation = false; directPublished = false; matcherPublicationDeferred = false;
        dispatched = false; directFailure = null;
        admissionDependency = null;
        admissionResolved = false;
        admissionRejection = null;
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
        prepareTreasuryDeltas(laneMask, true);
        resetCompletions(runtime.topology().accountLaneCount());
        // Direct events expose their completion through the event-owned padded bitset.  Do not
        // register per-Lane Owner notification expectations; that queue is only needed by the
        // legacy Owner-dispatched settlement path.
    }

    /** Owner 在交给 Matcher 前选定撤单业务，Lane 只应用接受的撤单。 */
    void cancellation(long userId) {
        if (!direct || dispatched || directPublished || userId <= 0)
            throw new IllegalStateException("invalid direct cancellation preparation");
        cancellation = true;
        cancellationUserId = userId;
    }

    public void triggerCompletion(com.surprising.aeron.service.state.model.CoreTriggerOrderState trigger,
                                  long timestamp) {
        if (!direct || dispatched || directPublished || batchPlanCount != 1 || trigger == null
                || timestamp < 0 || sourceTrigger != null)
            throw new IllegalStateException("invalid direct trigger preparation");
        sourceTrigger = trigger;
        triggeredAt = timestamp;
    }

    void replacement(com.surprising.aeron.service.command.order.ResolvedMatchingAdmission admission, int assetId) {
        if (!direct || dispatched || directPublished || batchPlanCount != 1 || admission == null)
            throw new IllegalStateException("invalid direct replacement preparation");
        replacement = admission;
        replacementAssetId = assetId;
    }

    long replacementIdentityAllocations() { return replacementIdentityAllocations; }

    public OrderRuntime admittedOrder() {
        if (!direct || batchPlanCount != 1) throw new IllegalStateException("single direct order is required");
        return batchStorage.admittedOrders[0];
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

    /** Lane readiness includes a normal PLACE admission queued ahead of this direct event. */
    public boolean readyForLane() {
        if (!ready()) return false;
        if (admissionResolved || admissionDependency == null) return true;
        return admissionDependency.complete();
    }

    /**
     * Matcher-side admission fence.  The Matcher may own the command token immediately, but it
     * must not mutate its order book until the Account Lane has decided whether the reservation
     * is valid.  This wait stays off the Owner thread and preserves the direct result handoff.
     */
    public void awaitAdmission() {
        while (!admissionResolved) {
            PlaceAdmissionEvent dependency = admissionDependency;
            if (dependency == null || dependency.complete()) {
                resolveAdmissionDependency();
                return;
            }
            Thread.onSpinWait();
        }
    }

    public RuntimeException admissionRejection() {
        return admissionRejection;
    }

    public String admissionResultCode() {
        RuntimeException rejection = admissionRejection;
        if (rejection instanceof CoreStateRejectedException stateRejected)
            return stateRejected.code();
        if (rejection instanceof ArithmeticException) return "ARITHMETIC_OVERFLOW";
        return rejection == null ? "SUCCESS" : "INVALID_COMMAND";
    }

    /** Owner or the target Lane copies the completed admission result before the event is cleared. */
    public void admissionResult(RuntimeException rejection) {
        if (!direct || admissionDependency == null)
            throw new IllegalStateException("direct settlement has no place admission dependency");
        if (admissionResolved) {
            if (admissionRejection != rejection)
                throw new IllegalStateException("place admission result changed");
            return;
        }
        admissionRejection = rejection;
        admissionResolved = true;
        signalAdmissionReady();
    }

    /** Wakes every Lane parked behind the direct event's admission or Matcher publication. */
    void signalAdmissionReady() {
        if (direct && runtime != null) runtime.signalDirectSettlement(routedLaneMask);
    }

    private void resolveAdmissionDependency() {
        if (admissionResolved || admissionDependency == null) return;
        PlaceAdmissionEvent dependency = admissionDependency;
        if (!dependency.complete())
            throw new IllegalStateException("place admission is not complete");
        admissionResult(dependency.rejection());
    }

    /** Matcher 自身用于区分结果已构建和已经允许 Lane 消费，不新增就绪状态。 */
    public boolean resultPrepared() { return !direct || firstDirectResult != null || directFailure != null; }

    /** Defer Owner wake-up while the Matcher still owns the slot and event reference. */
    public void beginMatcherPublication() {
        if (!direct || directPublished || matcherPublicationDeferred) {
            throw new IllegalStateException("invalid matcher publication start");
        }
        matcherPublicationDeferred = true;
    }

    /** Publish the deferred wake-up after the Matcher has released or retained its SPSC slot. */
    public void completeMatcherPublication() {
        if (!direct || !matcherPublicationDeferred) return;
        matcherPublicationDeferred = false;
        if (resultPrepared()) notifyDirectPublication();
    }

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
            if (authorizedCancellations.isEmpty() && replacement == null || containsTrade)
                throw new IllegalStateException("direct matcher returned an unreconciled partial outcome");
        }
        if (cancellation) {
            batchPlans[index].buildDirectCancellation(directCoreSequence, cancellationUserId,
                    batchStorage.admittedOrders[index], result, runtime);
            return;
        }
        batchPlans[index].buildDirect(directCoreSequence, batchStorage.admittedOrders[index],
                batchInstruments[index], result, runtime);
        batchPlans[index].preCancellationsFromResult(result.cancellations(), authorizedCancellations,
                replacement == null ? 0 : replacement.originalOrderId());
        if (replacement != null && !result.accepted()) batchPlans[index].omitUnplacedOrder();
        if (sourceTrigger != null) {
            batchPlans[index].completeTrigger(result.accepted()
                    ? RuntimeCommandProcessor.prepareMatchedTriggerCompletion(sourceTrigger,
                            batchStorage.admittedOrders[index].orderId(), triggeredAt)
                    : RuntimeCommandProcessor.prepareRejectedTriggerCompletion(sourceTrigger,
                            result.resultCode(), triggeredAt));
        }
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
        if (!matcherPublicationDeferred) notifyDirectPublication();
    }

    public void failDirect(Throwable failure) {
        if (!direct || directPublished) return;
        directFailure = java.util.Objects.requireNonNull(failure);
        if (!matcherPublicationDeferred) notifyDirectPublication();
    }

    private void notifyDirectPublication() {
        TradingRuntimeState completionRuntime = runtime;
        long completionLanes = routedLaneMask;
        releaseMatcherCompletionBeforeSignal();
        // Deterministic ~1/64 sampling; use existing per-Lane cache-line padding for times.
        if (LATENCY_DIAGNOSTICS && (Long.hashCode(directCoreSequence * 0x9e3779b97f4a7c15L) & 63) == 0)
            matcherPublishedNanos = System.nanoTime();
        // 最后一次访问事件：Lane 观察到 ready 后可能立即完成，Owner 随即复用事件。
        directPublished = true;
        completionRuntime.signalDirectSettlement(completionLanes);
    }

    /** 由 Matcher pipeline 设置；复用已有 Context，不为每笔直达命令创建回调对象。 */
    public void matcherCompletionRoute(MatcherCompletionRoute route, int matcherShard) {
        if (!direct || directPublished || matcherCompletionRoute != null || matcherShard < 0) {
            throw new IllegalStateException("invalid matcher completion release callback");
        }
        matcherCompletionRoute = java.util.Objects.requireNonNull(route);
        matcherCompletionShard = matcherShard;
    }

    private void releaseMatcherCompletionBeforeSignal() {
        MatcherCompletionRoute route = matcherCompletionRoute;
        int shard = matcherCompletionShard;
        matcherCompletionRoute = null;
        matcherCompletionShard = -1;
        if (route != null) route.releaseMatcherSubmission(shard);
    }

    public com.surprising.aeron.service.matching.CoreMatchingResult firstDirectResult() {
        requireDirectResult(); return firstDirectResult;
    }
    /** A direct Matcher failure is prepared too, but has no result that can advance evidence. */
    public boolean directResultAvailable() {
        return direct && directPublished && directFailure == null && firstDirectResult != null;
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
        prepareTreasuryDeltas(requiredLaneMask, plan.tradeCount() != 0);
        collectedFundsDelta = RuntimeFundsDelta.empty();
        collected = false;
        resetCompletions(laneCount);
        runtime.expectMatcherSettlement(requiredLaneMask);
        return this;
    }

    void admissionDependency(PlaceAdmissionEvent admission) {
        if (!direct || admission == null || admissionDependency != null)
            throw new IllegalStateException("invalid place admission dependency");
        admissionDependency = admission;
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
        prepareTreasuryDeltas(requiredLaneMask, treasuryTrades);
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
        direct = false; cancellation = false; directPublished = false; directFailure = null; dispatched = false;
        matcherPublicationDeferred = false;
        cancellationUserId = 0;
        replacement = null;
        replacementAssetId = 0;
        replacementIdentityAllocations = 0;
        sourceTrigger = null;
        triggeredAt = 0;
        matcherCompletionRoute = null;
        matcherCompletionShard = -1;
        admissionDependency = null;
        admissionResolved = false;
        admissionRejection = null;
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
        // A direct normal PLACE is queued behind its admission on the same Lane.  If admission
        // rejected, consume this already-submitted matcher fact as a no-op so the Owner can
        // retire both pooled events without touching account state.
        resolveAdmissionDependency();
        if (admissionRejection != null) {
            if (changes != null) changes.prepareLaneTerminal(laneId, identities, lane, runtime);
            publishCompletion(lane, startedNanos);
            return;
        }
        if (admissionDependency != null
                && laneId == runtime.topology().accountLaneId(plan.activeUserId())) {
            OrderRuntime admitted = lane.orders.get(plan.takerOrderId());
            if (admitted == null) throw new IllegalStateException("place admission order is missing from Lane");
            batchStorage.admittedOrders[0] = admitted;
        }
        if (changes == null) runtime.enterLaneCommandScope(lane);
        else runtime.enterMatcherSettlementScope(lane, changes);
        try {
            RuntimeTreasuryDelta delta = !treasuryTrades
                    ? EMPTY_TREASURY_DELTA : touchedLaneTreasuryDeltas[laneSlot(laneId)];
            if (batchPlans == null) {
                applyPlan(lane, laneId, plan, instrument,
                        baseAssetId, quoteAssetId, settleAssetId, delta);
                if (commitSequence != 0 || cancellation) {
                    runtime.stampMatcherOrders(lane, plan, commitTimestamp, commitClusterPosition);
                }
            }
            else for (int index = 0; index < batchPlanCount; index++) {
                MatcherSettlementPlan batchPlan = batchPlans[index];
                if ((batchPlan.requiredLaneMask() & laneMask) == 0) continue;
                applyPlan(lane, laneId, batchPlan, batchInstruments[index],
                        batchBaseAssetIds[index], batchQuoteAssetIds[index],
                        batchSettleAssetIds[index], delta);
                if (commitSequence != 0 || cancellation) {
                    runtime.stampMatcherOrders(
                            lane, batchPlan, commitTimestamp, commitClusterPosition);
                }
            }
            if (changes != null) {
                changes.prepareLaneTerminal(laneId, identities, lane, runtime);
            }
            if (resultTarget != null && changes != null
                    && laneId == runtime.topology().accountLaneId(plan.activeUserId()))
                LaneOrderResultTarget.capture(resultTarget, changes.laneDeltas[laneId], identities, lane);
            if (commitSequence != 0) {
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
        long finishedNanos = System.nanoTime();
        completionRuntime.recordMatcherLaneOperation(lane, finishedNanos - startedNanos);
        int completionOffset = laneId * CACHE_LINE_LONGS;
        if ((long) LONGS.getAcquire(completedLanes, completionOffset) != 0) {
            throw new IllegalStateException("account lane completed the same matcher fact twice");
        }
        boolean directCompletion = direct;
        if (LATENCY_DIAGNOSTICS && matcherPublishedNanos != 0) {
            completedLanes[completionOffset + 1] = startedNanos;
            completedLanes[completionOffset + 2] = finishedNanos;
        }
        LONGS.setRelease(completedLanes, completionOffset, 1L);
        if (directCompletion) completionRuntime.publishDirectMatcherSettlementReady(laneId);
        else completionRuntime.publishMatcherSettlementReady(laneId, completionSequence);
    }

    private void applyPlan(AccountLaneState lane, int laneId, MatcherSettlementPlan value,
                           CoreInstrumentState valueInstrument, int valueBaseAssetId,
                           int valueQuoteAssetId, int valueSettleAssetId, RuntimeTreasuryDelta delta) {
        if (cancellation) {
            if (!value.rejectedTaker())
                runtime.cancelOrderInLane(value.activeUserId(), value.takerOrderId(),
                        commitTimestamp, commitClusterPosition);
            return;
        }
        if (replacement != null) {
            applyReplacement(lane, value);
            if (!firstDirectResult.accepted()) return;
        }
        value.validateDirectLane(lane, runtime, identities, valueInstrument);
        for (int index = 0; replacement == null && index < value.preCancellationCount(); index++) {
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

    private void applyReplacement(AccountLaneState lane, MatcherSettlementPlan value) {
        if (!runtime.currentLaneOwns(replacement.userId())) return;
        long userId = replacement.userId();
        OrderRuntime original = lane.orders.get(replacement.originalOrderId());
        UserRuntime user = lane.users.get(userId);
        if (original == null || original.revision() != replacement.originalOrderRevision()
                || user == null || user.revision() != replacement.userRevision())
            throw new IllegalStateException("replace admission changed before Lane execution");
        for (int index = 0; index < value.preCancellationCount(); index++)
            runtime.cancelOrderInLane(userId, value.preCancellationOrderId(index),
                    commitTimestamp, commitClusterPosition);
        if (!firstDirectResult.accepted()) return;
        OrderRuntime prepared = batchStorage.admittedOrders[0];
        if (lane.orders.get(replacement.originalOrderId()).status() != CoreOrderStatus.CANCELED)
            throw new IllegalStateException("accepted replacement did not cancel original order");
        long allocationsBefore = lane.clientIdentityAllocations;
        long clientKey = identities.prepareClientKeyInLane(lane, userId, prepared.clientOrderId()).key();
        replacementIdentityAllocations = lane.clientIdentityAllocations - allocationsBefore;
        runtime.captureBalanceBefore(userId, replacementAssetId);
        runtime.placeOrderInLane(lane, userId, replacement.resolved(), directCommandId,
                replacement.requiredReservationUnits(), clientKey, prepared.symbolId(), replacementAssetId,
                directCoreSequence, prepared, commitTimestamp, commitClusterPosition);
        runtime.publishUser(userId, lane.users.get(userId));
        runtime.publishOrder(prepared.orderId(), prepared);
        runtime.publishReservation(prepared.orderId(), lane.reservations.get(prepared.orderId()));
        runtime.captureBalanceAfter(lane, userId, replacementAssetId);
    }

    /** Treasury slots follow the compact touched-Lane order used by laneSlot(). */
    private void prepareTreasuryDeltas(long laneMask, boolean trades) {
        if (!trades) {
            if (touchedLaneTreasuryDeltas != null) {
                for (RuntimeTreasuryDelta delta : touchedLaneTreasuryDeltas) delta.clear();
            }
            return;
        }
        int touchedLaneCount = Long.bitCount(laneMask);
        if (touchedLaneTreasuryDeltas == null || touchedLaneTreasuryDeltas.length != touchedLaneCount) {
            touchedLaneTreasuryDeltas = new RuntimeTreasuryDelta[touchedLaneCount];
            for (int index = 0; index < touchedLaneCount; index++) {
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
    boolean hasChanges() { return changes != null; }
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
        matcherPublishedNanos = 0;
        observedCompletedLaneMask = 0;
        int length = Math.multiplyExact(laneCount, CACHE_LINE_LONGS);
        if (completedLanes == null || completedLanes.length != length) completedLanes = new long[length];
        else {
            long lanes = routedLaneMask();
            while (lanes != 0) {
                int laneId = Long.numberOfTrailingZeros(lanes);
                lanes &= lanes - 1;
                LONGS.setRelease(completedLanes, laneId * CACHE_LINE_LONGS, 0L);
            }
        }
    }
}
