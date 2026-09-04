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
    private long[] orderIds;
    private int orderCount;
    private long commitTimestamp;
    private long commitClusterPosition;
    private int laneId;
    private boolean commitLane;
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
        if (orderIds == null) orderIds = new long[1];
        orderIds[0] = targetOrderId;
        orderCount = 1;
        return prepare(sequence, ownerUserId, orderIds, orderCount, timestamp, clusterPosition,
                ownerLaneId, true, owner, identityRegistry, commandChanges);
    }

    LaneCancelEvent prepare(long sequence, long ownerUserId, long[] targetOrderIds, int targetOrderCount,
                            long timestamp, long clusterPosition, int ownerLaneId,
                            boolean commitAccountLane,
                            TradingRuntimeState owner,
                            RuntimeIdentityRegistry identityRegistry,
                            TradingRuntimeState.MatcherSettlementChanges commandChanges) {
        if (sequence <= 0 || ownerUserId <= 0 || targetOrderIds == null
                || targetOrderCount <= 0 || targetOrderCount > targetOrderIds.length
                || timestamp < 0 || clusterPosition < 0 || ownerLaneId < 0 || owner == null
                || commandChanges == null || coreSequence != 0 || completed) {
            throw new IllegalStateException("invalid Account Lane cancel event");
        }
        if (orderIds == null || orderIds.length < targetOrderCount) {
            orderIds = new long[targetOrderCount];
        }
        System.arraycopy(targetOrderIds, 0, orderIds, 0, targetOrderCount);
        coreSequence = sequence;
        userId = ownerUserId;
        orderCount = targetOrderCount;
        commitTimestamp = timestamp;
        commitClusterPosition = clusterPosition;
        laneId = ownerLaneId;
        commitLane = commitAccountLane;
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
            for (int index = 0; index < orderCount; index++) {
                long orderId = orderIds[index];
                runtime.cancelOrderInLane(userId, orderId);
                runtime.stampOrderInLane(lane, orderId, commitTimestamp, commitClusterPosition);
            }
            changes.prepareLaneTerminal(laneId, identities, lane);
            if (commitLane) {
                lane.applied(coreSequence);
                lane.committed(coreSequence);
                runtime.publishLaneHashes(lane);
            }
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
    public long orderId() { return orderIds[0]; }
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
        orderCount = 0;
        commitTimestamp = 0;
        commitClusterPosition = 0;
        laneId = 0;
        commitLane = false;
        COMPLETED.setRelease(this, false);
    }

    void discard() {
        if (complete()) throw new IllegalStateException("completed cancel event must be collected");
        changes = null;
        runtime = null;
        identities = null;
        coreSequence = 0;
        userId = 0;
        orderCount = 0;
        commitTimestamp = 0;
        commitClusterPosition = 0;
        laneId = 0;
        commitLane = false;
    }
}
