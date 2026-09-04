package com.surprising.aeron.service.state;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/** Sequence-local cancellation applied entirely by the order owner's Account Lane. */
public final class LaneCancelEvent implements SettlementLaneWorker.Command {
    private static final VarHandle COMPLETED;

    static {
        try {
            COMPLETED = MethodHandles.lookup().findVarHandle(LaneCancelEvent.class, "completed", boolean.class);
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private TradingRuntimeState runtime;
    private TradingRuntimeState.MatcherSettlementChanges changes;
    private RuntimeIdentityRegistry identities;
    private long coreSequence;
    private long userId;
    private long orderId;
    private long commitTimestamp;
    private long commitClusterPosition;
    private int laneId;
    @SuppressWarnings("unused")
    private volatile boolean completed;

    LaneCancelEvent prepare(long sequence, long ownerUserId, long targetOrderId,
                            long timestamp, long clusterPosition, int ownerLaneId,
                            TradingRuntimeState owner,
                            RuntimeIdentityRegistry identityRegistry,
                            TradingRuntimeState.MatcherSettlementChanges commandChanges) {
        if (sequence <= 0 || ownerUserId <= 0 || targetOrderId <= 0 || timestamp < 0 || clusterPosition < 0
                || ownerLaneId < 0 || owner == null || commandChanges == null || coreSequence != 0 || completed) {
            throw new IllegalStateException("invalid Account Lane cancel event");
        }
        coreSequence = sequence;
        userId = ownerUserId;
        orderId = targetOrderId;
        commitTimestamp = timestamp;
        commitClusterPosition = clusterPosition;
        laneId = ownerLaneId;
        runtime = owner;
        identities = identityRegistry;
        changes = commandChanges;
        return this;
    }

    @Override
    public void execute(AccountLaneState lane) {
        if (lane.laneId() != laneId) throw new IllegalStateException("cancel reached the wrong Account Lane");
        long startedNanos = System.nanoTime();
        runtime.enterMatcherSettlementScope(lane, changes);
        try {
            runtime.cancelOrderInLane(userId, orderId);
            runtime.stampOrderInLane(lane, orderId, commitTimestamp, commitClusterPosition);
            changes.prepareLaneTerminal(laneId, identities, lane);
            lane.applied(coreSequence);
            lane.committed(coreSequence);
            runtime.publishLaneHashes(lane);
        } finally {
            runtime.exitMatcherSettlementScope(lane, changes);
        }
        runtime.recordMatcherLaneOperation(lane, System.nanoTime() - startedNanos);
        TradingRuntimeState completionRuntime = runtime;
        int completionLaneId = laneId;
        long completionSequence = coreSequence;
        COMPLETED.setRelease(this, true);
        completionRuntime.publishMatcherSettlementReady(completionLaneId, completionSequence);
    }

    public long coreSequence() { return coreSequence; }
    public long userId() { return userId; }
    public long orderId() { return orderId; }
    public int laneId() { return laneId; }
    public long requiredLaneMask() { return 1L << laneId; }
    public boolean complete() { return (boolean) COMPLETED.getAcquire(this); }
    RuntimeIdentityRegistry identities() { return identities; }

    TradingRuntimeState.MatcherSettlementChanges takeChanges() {
        if (!complete() || changes == null) throw new IllegalStateException("cancel event is incomplete");
        TradingRuntimeState.MatcherSettlementChanges result = changes;
        changes = null;
        return result;
    }

    void clear() {
        if (!complete() || changes != null) throw new IllegalStateException("cancel event was not collected");
        runtime = null;
        identities = null;
        coreSequence = 0;
        userId = 0;
        orderId = 0;
        commitTimestamp = 0;
        commitClusterPosition = 0;
        laneId = 0;
        COMPLETED.setRelease(this, false);
    }

    void discard() {
        if (complete()) throw new IllegalStateException("completed cancel event must be collected");
        changes = null;
        runtime = null;
        identities = null;
        coreSequence = 0;
        userId = 0;
        orderId = 0;
        commitTimestamp = 0;
        commitClusterPosition = 0;
        laneId = 0;
    }
}
