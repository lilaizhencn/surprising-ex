package com.surprising.aeron.service.state;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Immutable matcher fact routed once to every Account Lane it touches.
 * Mutable completion state is isolated from the fact payload and is only used by the owner coordinator.
 */
public final class MatcherSettlementEvent implements SettlementLaneWorker.Command {
    private static final RuntimeTreasuryDelta EMPTY_TREASURY_DELTA = new RuntimeTreasuryDelta(1);
    private static final VarHandle COMPLETED_LANE_MASK;

    static {
        try {
            COMPLETED_LANE_MASK = MethodHandles.lookup().findVarHandle(
                    MatcherSettlementEvent.class, "completedLaneMask", long.class);
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }


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
    @SuppressWarnings("FieldMayBeFinal")
    private long completedLaneMask;
    private boolean collected;

    MatcherSettlementEvent() {
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
                ? runtime.acquireMatcherSettlementChanges() : null;
        if (changes != null) {
            changes.ensureOrderCapacity(Math.addExact(plan.orderCount(), plan.preCancellationCount()));
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
        COMPLETED_LANE_MASK.set(this, 0L);
        return this;
    }

    MatcherSettlementEvent prepareBatch(
            long commitSequence, long requiredLaneMask,
            long commitTimestamp, long commitClusterPosition,
            MatcherSettlementPlan[] plans, TradingRuntimeState runtime,
            RuntimeIdentityRegistry identities, CoreInstrumentState[] instruments,
            int[] baseAssetIds, int[] quoteAssetIds, int[] settleAssetIds, int laneCount) {
        if (commitSequence <= 0 || commitTimestamp < 0 || commitClusterPosition < 0
                || requiredLaneMask == 0 || plans == null || plans.length == 0 || runtime == null
                || identities == null || instruments == null || instruments.length != plans.length
                || baseAssetIds == null || baseAssetIds.length != plans.length
                || quoteAssetIds == null || quoteAssetIds.length != plans.length
                || settleAssetIds == null || settleAssetIds.length != plans.length || laneCount <= 0) {
            throw new IllegalArgumentException("invalid matcher settlement batch event");
        }
        long coreSequence = plans[0].coreSequence();
        int expectedOrders = 0;
        for (MatcherSettlementPlan value : plans) {
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
        this.changes = runtime.acquireMatcherSettlementChanges();
        changes.ensureOrderCapacity(expectedOrders);
        treasuryTrades = hasTrade(plans);
        prepareTreasuryDeltas(laneCount, treasuryTrades);
        collectedFundsDelta = RuntimeFundsDelta.empty();
        collected = false;
        COMPLETED_LANE_MASK.set(this, 0L);
        return this;
    }

    void clear() {
        if (!complete() || changes != null) {
            throw new IllegalStateException("cannot recycle an incomplete matcher settlement");
        }
        plan = null;
        batchPlans = null;
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
        long startedNanos = System.nanoTime();
        int laneId = lane.laneId();
        long laneMask = 1L << laneId;
        if ((requiredLaneMask & laneMask) == 0) {
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
            else for (int index = 0; index < batchPlans.length; index++) {
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
                changes.prepareLaneTerminal(laneId, identities, lane);
            }
            if (commitSequence != 0) {
                lane.applied(commitSequence);
                lane.committed(commitSequence);
                runtime.publishLaneHashes(lane);
            }
        } finally {
            if (changes == null) runtime.exitLaneCommandScope(lane);
            else runtime.exitMatcherSettlementScope(lane, changes);
        }
        // The owner may recycle the event as soon as the final completed bit is visible. Keep
        // notification dependencies in Lane-local variables before publishing that bit.
        TradingRuntimeState completionRuntime = runtime;
        long completionSequence = plan.coreSequence();
        completionRuntime.recordMatcherLaneOperation(lane, System.nanoTime() - startedNanos);
        long previous = (long) COMPLETED_LANE_MASK.getAndBitwiseOrRelease(this, laneMask);
        if ((previous & laneMask) != 0) {
            throw new IllegalStateException("account lane completed the same matcher fact twice");
        }
        if ((previous | laneMask) == requiredLaneMask) {
            completionRuntime.publishMatcherSettlementReady(laneId, completionSequence);
        }
    }

    private void applyPlan(AccountLaneState lane, int laneId, MatcherSettlementPlan value,
                           CoreInstrumentState valueInstrument, int valueBaseAssetId,
                           int valueQuoteAssetId, int valueSettleAssetId, RuntimeTreasuryDelta delta) {
        for (int index = 0; index < value.preCancellationCount(); index++) {
            long orderId = value.preCancellationOrderId(index);
            OrderRuntime order = lane.orders.get(orderId);
            if (order != null && order.status() == CoreOrderStatus.OPEN) {
                runtime.cancelOrderInLane(order.userId(), orderId);
            }
        }
        if (runtime.productLine().isDerivative()) {
            RuntimePerpetualMatchProcessor.applyLane(value.takerOrderId(), value, laneId,
                    runtime, identities, valueInstrument, valueSettleAssetId, delta);
        } else {
            RuntimeSpotMatchProcessor.applyLane(value.takerOrderId(), value, laneId,
                    runtime, valueInstrument, valueBaseAssetId, valueQuoteAssetId, delta);
        }
        runtime.completeMatcherPendingReservations(lane, value);
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

    private static boolean hasTrade(MatcherSettlementPlan[] plans) {
        for (MatcherSettlementPlan value : plans) if (value.tradeCount() != 0) return true;
        return false;
    }

    public long commitSequence() { return commitSequence; }
    public long requiredLaneMask() { return requiredLaneMask; }
    public long completedLaneMask() {
        return (long) COMPLETED_LANE_MASK.getAcquire(this);
    }
    public boolean complete() { return completedLaneMask() == requiredLaneMask; }
    MatcherSettlementPlan plan() { return plan; }
    int planCount() { return batchPlans == null ? 1 : batchPlans.length; }
    MatcherSettlementPlan plan(int index) { return batchPlans == null ? plan : batchPlans[index]; }
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
}
