package com.surprising.aeron.service.state;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Immutable matcher fact routed once to every Account Lane it touches.
 * Mutable completion state is isolated from the fact payload and is only used by the owner coordinator.
 */
public final class MatcherSettlementEvent implements SettlementLaneWorker.Command {
    private static final RuntimeTreasuryDelta EMPTY_TREASURY_DELTA = new RuntimeTreasuryDelta(1);
    private static final int COMPLETION_STRIDE = 8;
    private static final VarHandle LANE_COMPLETION = MethodHandles.arrayElementVarHandle(long[].class);


    private final long commitSequence;
    private final long requiredLaneMask;
    private final long stateContribution;
    private final long fundsContribution;
    private final long commitTimestamp;
    private final long commitClusterPosition;
    private final MatcherSettlementPlan plan;
    private final TradingRuntimeState runtime;
    private final RuntimeIdentityRegistry identities;
    private final CoreInstrumentState instrument;
    private final int baseAssetId;
    private final int quoteAssetId;
    private final int settleAssetId;
    private final RuntimeTreasuryDelta[] laneTreasuryDeltas;
    private final long[] laneCompletions;
    private boolean collected;

    MatcherSettlementEvent(long commitSequence, long requiredLaneMask,
                           long stateContribution, long fundsContribution,
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
        this.stateContribution = stateContribution;
        this.fundsContribution = fundsContribution;
        this.commitTimestamp = commitTimestamp;
        this.commitClusterPosition = commitClusterPosition;
        this.plan = plan;
        this.runtime = runtime;
        this.identities = identities;
        this.instrument = instrument;
        this.baseAssetId = baseAssetId;
        this.quoteAssetId = quoteAssetId;
        this.settleAssetId = settleAssetId;
        laneCompletions = new long[Math.multiplyExact(laneCount, COMPLETION_STRIDE)];
        if (plan.tradeEventCount() == 0) {
            laneTreasuryDeltas = null;
        } else {
            laneTreasuryDeltas = new RuntimeTreasuryDelta[laneCount];
            for (int laneId = 0; laneId < laneCount; laneId++) {
                if ((requiredLaneMask & 1L << laneId) != 0) {
                    laneTreasuryDeltas[laneId] = new RuntimeTreasuryDelta();
                }
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
        runtime.inLaneCommandScope(lane, ignored -> {
            RuntimeTreasuryDelta delta = laneTreasuryDeltas == null
                    ? EMPTY_TREASURY_DELTA : laneTreasuryDeltas[laneId];
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
                lane.applied(commitSequence, stateContribution, fundsContribution);
                lane.committed(commitSequence);
                runtime.publishLaneHashes(lane);
            }
            return null;
        });
        runtime.recordMatcherLaneOperation(lane, System.nanoTime() - startedNanos);
        int completionIndex = Math.multiplyExact(laneId, COMPLETION_STRIDE);
        if ((long) LANE_COMPLETION.getAcquire(laneCompletions, completionIndex) != 0) {
            throw new IllegalStateException("account lane completed the same matcher fact twice");
        }
        LANE_COMPLETION.setRelease(laneCompletions, completionIndex, 1L);
    }

    public long commitSequence() { return commitSequence; }
    public long requiredLaneMask() { return requiredLaneMask; }
    public long completedLaneMask() {
        long completed = 0;
        long pending = requiredLaneMask;
        while (pending != 0) {
            int laneId = Long.numberOfTrailingZeros(pending);
            if ((long) LANE_COMPLETION.getAcquire(
                    laneCompletions, laneId * COMPLETION_STRIDE) != 0) {
                completed |= 1L << laneId;
            }
            pending &= pending - 1;
        }
        return completed;
    }
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
        if (laneTreasuryDeltas == null) return EMPTY_TREASURY_DELTA;
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
