package com.surprising.aeron.service.state;
import com.surprising.aeron.service.exception.CoreStateRejectedException;
import com.surprising.aeron.service.lane.SettlementLaneWorker;
import com.surprising.aeron.protocol.CoreResultCode;
import java.util.UUID;

/** One-way place admission owned and completed by exactly one Account Lane. */
public final class PlaceAdmissionEvent implements SettlementLaneWorker.Command {
    private long coreSequence;
    private long timestamp, position;
    private long userId;
    private ResolvedPlaceOrder order;
    private UUID commandId;
    private long openInterestSteps;
    private boolean lifecycleSettled;
    private boolean fundingInProgress;
    private RuntimeIdentityRegistry.PreparedClientKey preparedClientKey;
    private int symbolId;
    private int matcherShard;
    private int assetId;
    private int laneId;
    private TradingRuntimeState runtime;
    private RuntimeIdentityRegistry identities;
    private long identityAllocations;
    private long reservedAmount;
    private long admittedAccountVersion;
    private RuntimeException rejection;
    /** Volatile publication is sufficient: all payload fields are written before completion. */
    private volatile boolean completed;

    PlaceAdmissionEvent() {
    }

    PlaceAdmissionEvent prepare(long coreSequence, long userId, ResolvedPlaceOrder order, UUID commandId,
                                long openInterestSteps, boolean lifecycleSettled, boolean fundingInProgress,
                                int symbolId, int assetId, int laneId, int matcherShard, TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                long timestamp, long position) {
        if (timestamp < 0 || position < 0 || coreSequence <= 0 || userId <= 0 || order == null || commandId == null || openInterestSteps < 0
                || symbolId < 0 || assetId < 0
                || laneId < 0 || matcherShard < 0 || runtime == null) {
            throw new IllegalArgumentException("invalid place admission event");
        }
        this.coreSequence = coreSequence;
        this.timestamp = timestamp;
        this.position = position;
        this.userId = userId;
        this.order = order;
        this.commandId = commandId;
        this.openInterestSteps = openInterestSteps;
        this.lifecycleSettled = lifecycleSettled;
        this.fundingInProgress = fundingInProgress;
        this.preparedClientKey = null;
        this.identities = identities;
        identityAllocations = 0;
        this.symbolId = symbolId;
        this.assetId = assetId;
        this.laneId = laneId;
        this.matcherShard = matcherShard;
        this.runtime = runtime;
        admittedAccountVersion = 0;
        rejection = null;
        reservedAmount = 0;
        completed = false;
        return this;
    }

    void clear() {
        if (!complete()) throw new IllegalStateException("cannot recycle an incomplete place admission");
        order = null;
        commandId = null;
        lifecycleSettled = false;
        fundingInProgress = false;
        preparedClientKey = null;
        runtime = null;
        identities = null;
        admittedAccountVersion = 0;
        rejection = null;
        reservedAmount = 0;
        matcherShard = 0;
    }

    @Override
    public void execute(AccountLaneState lane) {
        long startedNanos = System.nanoTime();
        if (lane.laneId() != runtime.topology().accountLaneId(userId)) {
            throw new IllegalStateException("place admission reached the wrong Account Lane");
        }
        long allocationsBefore = lane.clientIdentityAllocations;
        try {
            runtime.enterLaneCommandScope(lane);
            try {
                preparedClientKey = identities.prepareClientKeyInLane(lane, userId, order.clientOrderId());
                String positionIdentity = order.positionSide() == com.surprising.aeron.protocol.CorePositionSide.NET
                        ? order.symbol() : order.symbol() + ':' + order.positionSide().name();
                long positionKey = identities.findPositionKeyValueInLane(lane, userId, positionIdentity);
                long requiredReservation = RuntimeOrderAdmission.requiredReservationPrepared(
                        runtime, userId, order, openInterestSteps,
                        lane.admissionOrderIndex(symbolId), preparedClientKey.key(), symbolId, positionKey,
                        lifecycleSettled, fundingInProgress);
                reservedAmount = requiredReservation;
                runtime.placeOrderInLane(lane, userId, order, commandId,
                        requiredReservation, preparedClientKey.key(), symbolId, assetId, coreSequence, null, timestamp, position);
                admittedAccountVersion = lane.users.get(userId).revision();
            } finally {
                runtime.exitLaneCommandScope(lane);
            }
        } catch (CoreStateRejectedException | ArithmeticException | IllegalArgumentException failure) {
            identities.rollbackClientKeyInLane(lane, userId, order.clientOrderId(), preparedClientKey);
            preparedClientKey = null;
            rejection = failure;
        }
        identityAllocations = lane.clientIdentityAllocations - allocationsBefore;
        // Capture every publication dependency before completed becomes visible to the owner,
        // which is then allowed to clear and recycle this event immediately.
        TradingRuntimeState completionRuntime = runtime;
        int completionLaneId = laneId;
        long completionSequence = coreSequence;
        completionRuntime.recordAdmissionLaneOperation(lane, System.nanoTime() - startedNanos);
        CoreResultCode resultCode = rejection == null ? CoreResultCode.NONE
                : rejection instanceof CoreStateRejectedException stateRejected
                ? CoreResultCode.fromRejectionCode(stateRejected.code())
                : rejection instanceof ArithmeticException ? CoreResultCode.ARITHMETIC_OVERFLOW
                : CoreResultCode.INVALID_COMMAND;
        completionRuntime.publishAdmissionReceipt(completionLaneId, matcherShard,
                completionSequence, order.orderId(),
                admittedAccountVersion, reservedAmount,
                rejection == null, resultCode.wireCode());
        completed = true;
        completionRuntime.signalOwnerCompletion();
    }

    public boolean complete() {
        return completed;
    }

    /** Abort a prepared event that was never submitted to a Lane. */
    void discard() {
        if (complete()) throw new IllegalStateException("completed place admission must be collected");
        order = null;
        commandId = null;
        lifecycleSettled = false;
        fundingInProgress = false;
        preparedClientKey = null;
        runtime = null;
        identities = null;
        admittedAccountVersion = 0;
        rejection = null;
        reservedAmount = 0;
        identityAllocations = 0;
        coreSequence = 0;
        userId = 0;
        laneId = 0;
        matcherShard = 0;
        completed = false;
    }


    public long takeIdentityAllocations() { long result = identityAllocations; identityAllocations = 0; return result; }
    public long coreSequence() { return coreSequence; }
    public long userId() { return userId; }
    public int laneId() { return laneId; }
    public long orderId() { return order.orderId(); }
    public int assetId() { return assetId; }
    public long clientKey() { return preparedClientKey.key(); }
    public ResolvedPlaceOrder resolvedOrder() {
        if (!complete() || order == null || rejection != null) {
            throw new IllegalStateException("place admission is not accepted");
        }
        return order;
    }

    /**
     * Immutable matcher input is available as soon as the owner prepares the admission.  The
     * matcher can therefore run in parallel with the account Lane; it must not wait for the Lane
     * to publish the mutable runtime order just to reconstruct these command fields.
     */
    public ResolvedPlaceOrder matchingOrder() {
        ResolvedPlaceOrder resolved = order;
        if (resolved == null) throw new IllegalStateException("place admission is not prepared");
        return resolved;
    }

    /** Owner-only immutable input used to preconstruct a direct settlement before Lane admission. */
    ResolvedPlaceOrder preparedOrder() {
        if (order == null) throw new IllegalStateException("place admission is not prepared");
        return order;
    }

    public RuntimeException rejection() {
        if (!complete()) throw new IllegalStateException("place admission is incomplete");
        return rejection;
    }
}
