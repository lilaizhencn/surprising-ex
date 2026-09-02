package com.surprising.aeron.service.state;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Immutable matcher fact routed once to every Account Lane it touches.
 * Mutable completion state is isolated from the fact payload and is only used by the owner coordinator.
 */
public final class MatcherSettlementEvent implements SettlementLaneWorker.Command {
    private static final VarHandle COMPLETED_LANE_MASK;
    private static final VarHandle FAILURE;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            COMPLETED_LANE_MASK = lookup.findVarHandle(MatcherSettlementEvent.class,
                    "completedLaneMask", long.class);
            FAILURE = lookup.findVarHandle(MatcherSettlementEvent.class, "failure", Throwable.class);
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private final long commitSequence;
    private final long requiredLaneMask;
    private final long stateContribution;
    private final long fundsContribution;
    private final MatcherSettlementPlan plan;
    private final TradingRuntimeState runtime;
    private final RuntimeIdentityRegistry identities;
    private final CoreInstrumentState instrument;
    private final int baseAssetId;
    private final int quoteAssetId;
    private final int settleAssetId;
    private final RuntimeTreasuryDelta[] laneTreasuryDeltas;
    private volatile long completedLaneMask;
    private volatile Throwable failure;
    private boolean collected;

    MatcherSettlementEvent(long commitSequence, long requiredLaneMask,
                           long stateContribution, long fundsContribution,
                           MatcherSettlementPlan plan, TradingRuntimeState runtime,
                           RuntimeIdentityRegistry identities, CoreInstrumentState instrument,
                           int baseAssetId, int quoteAssetId, int settleAssetId, int laneCount) {
        if (commitSequence < 0 || requiredLaneMask == 0 || plan == null || runtime == null
                || identities == null || instrument == null || laneCount <= 0) {
            throw new IllegalArgumentException("invalid immutable matcher settlement event");
        }
        this.commitSequence = commitSequence;
        this.requiredLaneMask = requiredLaneMask;
        this.stateContribution = stateContribution;
        this.fundsContribution = fundsContribution;
        this.plan = plan;
        this.runtime = runtime;
        this.identities = identities;
        this.instrument = instrument;
        this.baseAssetId = baseAssetId;
        this.quoteAssetId = quoteAssetId;
        this.settleAssetId = settleAssetId;
        laneTreasuryDeltas = new RuntimeTreasuryDelta[laneCount];
        for (int laneId = 0; laneId < laneCount; laneId++) {
            if ((requiredLaneMask & 1L << laneId) != 0) {
                laneTreasuryDeltas[laneId] =
                        new RuntimeTreasuryDelta(RuntimeTreasuryDelta.ORDER_BATCH_CAPACITY);
            }
        }
    }

    @Override
    public void execute(AccountLaneState lane) {
        long startedNanos = System.nanoTime();
        int laneId = lane.laneId();
        long laneMask = 1L << laneId;
        if ((requiredLaneMask & laneMask) == 0) {
            throw new IllegalStateException("matcher fact was routed to an unrelated account lane");
        }
        try {
            runtime.inLaneCommandScope(lane, ignored -> {
                RuntimeTreasuryDelta delta = laneTreasuryDeltas[laneId];
                if (runtime.productLine().isDerivative()) {
                    RuntimePerpetualMatchProcessor.applyLane(plan.takerOrderId(), plan, laneId,
                            runtime, identities, instrument, settleAssetId, delta);
                } else {
                    RuntimeSpotMatchProcessor.applyLane(plan.takerOrderId(), plan, laneId,
                            runtime, instrument, baseAssetId, quoteAssetId, delta);
                }
                if (commitSequence != 0) {
                    lane.applied(commitSequence, stateContribution, fundsContribution);
                }
                return null;
            });
        } catch (Throwable laneFailure) {
            FAILURE.compareAndSet(this, null, laneFailure);
        } finally {
            runtime.recordMatcherLaneOperation(laneId, System.nanoTime() - startedNanos);
            long previous = (long) COMPLETED_LANE_MASK.getAndBitwiseOr(this, laneMask);
            if ((previous & laneMask) != 0) {
                FAILURE.compareAndSet(this, null,
                        new IllegalStateException("account lane completed the same matcher fact twice"));
            }
        }
    }

    public long commitSequence() { return commitSequence; }
    public long requiredLaneMask() { return requiredLaneMask; }
    public long completedLaneMask() { return completedLaneMask; }
    public boolean complete() { return completedLaneMask() == requiredLaneMask; }
    MatcherSettlementPlan plan() { return plan; }
    RuntimeIdentityRegistry identities() { return identities; }
    CoreInstrumentState instrument() { return instrument; }
    int baseAssetId() { return baseAssetId; }
    int quoteAssetId() { return quoteAssetId; }
    int settleAssetId() { return settleAssetId; }

    RuntimeTreasuryDelta collectTreasuryDelta() {
        if (!complete()) return null;
        if (collected) throw new IllegalStateException("matcher settlement event was already collected");
        collected = true;
        Throwable laneFailure = failure;
        if (laneFailure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
        if (laneFailure instanceof Error error) throw error;
        if (laneFailure != null) throw new IllegalStateException("account lane settlement failed", laneFailure);
        RuntimeTreasuryDelta aggregate = null;
        for (int laneId = 0; laneId < laneTreasuryDeltas.length; laneId++) {
            RuntimeTreasuryDelta delta = laneTreasuryDeltas[laneId];
            if (delta == null) continue;
            if (aggregate == null) aggregate = delta;
            else aggregate.merge(delta);
        }
        if (aggregate == null) throw new IllegalStateException("matcher settlement event has no lane delta");
        return aggregate;
    }
}
