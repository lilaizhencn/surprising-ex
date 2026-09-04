package com.surprising.aeron.service.state;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.UUID;

/** Sequence-local replace/amend preparation performed entirely by the owning Account Lane. */
public final class LaneReplaceEvent implements SettlementLaneWorker.Command {
    private static final VarHandle COMPLETED;

    static {
        try {
            COMPLETED = MethodHandles.lookup().findVarHandle(LaneReplaceEvent.class, "completed", boolean.class);
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private TradingRuntimeState runtime;
    private TradingRuntimeState.MatcherSettlementChanges changes;
    private RuntimeIdentityRegistry identities;
    private ResolvedPlaceOrder replacement;
    private UUID commandId;
    private long coreSequence;
    private long userId;
    private long originalOrderId;
    private long[] preCancelOrderIds;
    private long requiredReservation;
    private long clientKey;
    private long commitTimestamp;
    private long commitClusterPosition;
    private int symbolId;
    private int assetId;
    private int laneId;
    @SuppressWarnings("unused")
    private volatile boolean completed;

    LaneReplaceEvent prepare(long sequence, long ownerUserId, long targetOrderId,
                             long[] capacityCancelOrderIds, ResolvedPlaceOrder replacementOrder,
                             UUID replacementCommandId,
                             long reservationUnits, long replacementClientKey,
                             int replacementSymbolId, int replacementAssetId,
                             long timestamp, long clusterPosition, int ownerLaneId,
                             TradingRuntimeState owner, RuntimeIdentityRegistry identityRegistry,
                             TradingRuntimeState.MatcherSettlementChanges commandChanges) {
        if (sequence <= 0 || ownerUserId <= 0 || targetOrderId <= 0 || capacityCancelOrderIds == null
                || replacementOrder == null
                || replacementCommandId == null || reservationUnits <= 0 || replacementClientKey < 0
                || replacementSymbolId < 0 || replacementAssetId < 0 || timestamp < 0 || clusterPosition < 0
                || ownerLaneId < 0 || owner == null || identityRegistry == null || commandChanges == null
                || coreSequence != 0 || completed) {
            throw new IllegalStateException("invalid Account Lane replace event");
        }
        coreSequence = sequence;
        userId = ownerUserId;
        originalOrderId = targetOrderId;
        preCancelOrderIds = capacityCancelOrderIds;
        replacement = replacementOrder;
        commandId = replacementCommandId;
        requiredReservation = reservationUnits;
        clientKey = replacementClientKey;
        symbolId = replacementSymbolId;
        assetId = replacementAssetId;
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
        if (lane.laneId() != laneId) throw new IllegalStateException("replace reached the wrong Account Lane");
        long startedNanos = System.nanoTime();
        runtime.enterMatcherSettlementScope(lane, changes);
        try {
            for (long orderId : preCancelOrderIds) runtime.cancelOrderInLane(userId, orderId);
            runtime.replaceOrderInLane(lane, userId, originalOrderId, replacement, commandId,
                    requiredReservation, clientKey, symbolId, assetId, coreSequence);
            runtime.stampOrderInLane(lane, originalOrderId, commitTimestamp, commitClusterPosition);
            for (long orderId : preCancelOrderIds) {
                runtime.stampOrderInLane(lane, orderId, commitTimestamp, commitClusterPosition);
            }
            runtime.stampOrderInLane(lane, replacement.orderId(), commitTimestamp, commitClusterPosition);
            changes.prepareLaneTerminal(laneId, identities, lane);
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
    public long originalOrderId() { return originalOrderId; }
    public long replacementOrderId() { return replacement.orderId(); }
    public int preCancellationCount() { return preCancelOrderIds.length; }
    public int laneId() { return laneId; }
    public long requiredLaneMask() { return 1L << laneId; }
    public boolean complete() { return (boolean) COMPLETED.getAcquire(this); }

    TradingRuntimeState.MatcherSettlementChanges takeChanges() {
        if (!complete() || changes == null) throw new IllegalStateException("replace event is incomplete");
        TradingRuntimeState.MatcherSettlementChanges result = changes;
        changes = null;
        return result;
    }

    void clear() {
        if (!complete() || changes != null) throw new IllegalStateException("replace event was not collected");
        runtime = null;
        identities = null;
        replacement = null;
        commandId = null;
        coreSequence = 0;
        userId = 0;
        originalOrderId = 0;
        preCancelOrderIds = null;
        requiredReservation = 0;
        clientKey = 0;
        commitTimestamp = 0;
        commitClusterPosition = 0;
        symbolId = 0;
        assetId = 0;
        laneId = 0;
        COMPLETED.setRelease(this, false);
    }
}
