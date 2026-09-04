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
    private TradingRuntimeState runtime;
    private RuntimeIdentityRegistry identities;
    private CoreInstrumentState instrument;
    private int baseAssetId;
    private int quoteAssetId;
    private int settleAssetId;
    private RuntimeTreasuryDelta[] touchedLaneTreasuryDeltas;
    private TradingRuntimeState.MatcherSettlementChanges changes;
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
                                   int baseAssetId, int quoteAssetId, int settleAssetId, int laneCount) {
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
        this.changes = commitSequence == 0 ? null : runtime.acquireMatcherSettlementChanges();
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

    void clear() {
        if (!complete() || changes != null) {
            throw new IllegalStateException("cannot recycle an incomplete matcher settlement");
        }
        plan = null;
        runtime = null;
        identities = null;
        instrument = null;
        collectedFundsDelta = RuntimeFundsDelta.empty();
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
        if (commitSequence == 0) runtime.enterLaneCommandScope(lane);
        else runtime.enterMatcherSettlementScope(lane, changes);
        try {
            for (int index = 0; index < plan.preCancellationCount(); index++) {
                long orderId = plan.preCancellationOrderId(index);
                OrderRuntime order = lane.orders.get(orderId);
                if (order != null && order.status() == CoreOrderStatus.OPEN) {
                    runtime.cancelOrderInLane(order.userId(), orderId);
                }
            }
            RuntimeTreasuryDelta delta = plan.tradeCount() == 0
                    ? EMPTY_TREASURY_DELTA : touchedLaneTreasuryDeltas[laneSlot(laneId)];
            if (runtime.productLine().isDerivative()) {
                RuntimePerpetualMatchProcessor.applyLane(plan.takerOrderId(), plan, laneId,
                        runtime, identities, instrument, settleAssetId, delta);
            } else {
                RuntimeSpotMatchProcessor.applyLane(plan.takerOrderId(), plan, laneId,
                        runtime, instrument, baseAssetId, quoteAssetId, delta);
            }
            runtime.completeMatcherPendingReservations(lane, plan);
            if (commitSequence != 0) {
                runtime.stampMatcherOrders(lane, plan, commitTimestamp, commitClusterPosition);
            }
            if (commitSequence != 0) {
                changes.prepareLaneTerminal(laneId, identities, lane);
                lane.applied(commitSequence);
                lane.committed(commitSequence);
                runtime.publishLaneHashes(lane);
            }
        } finally {
            if (commitSequence == 0) runtime.exitLaneCommandScope(lane);
            else runtime.exitMatcherSettlementScope(lane, changes);
        }
        runtime.recordMatcherLaneOperation(lane, System.nanoTime() - startedNanos);
        long previous = (long) COMPLETED_LANE_MASK.getAndBitwiseOrRelease(this, laneMask);
        if ((previous & laneMask) != 0) {
            throw new IllegalStateException("account lane completed the same matcher fact twice");
        }
        if ((previous | laneMask) == requiredLaneMask && commitSequence != 0) {
            runtime.publishMatcherSettlementReady(laneId, plan.coreSequence());
        }
    }

    public long commitSequence() { return commitSequence; }
    public long requiredLaneMask() { return requiredLaneMask; }
    public long completedLaneMask() {
        return (long) COMPLETED_LANE_MASK.getAcquire(this);
    }
    public boolean complete() { return completedLaneMask() == requiredLaneMask; }
    MatcherSettlementPlan plan() { return plan; }
    RuntimeIdentityRegistry identities() { return identities; }
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
        if (plan.tradeCount() == 0) return EMPTY_TREASURY_DELTA;
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
