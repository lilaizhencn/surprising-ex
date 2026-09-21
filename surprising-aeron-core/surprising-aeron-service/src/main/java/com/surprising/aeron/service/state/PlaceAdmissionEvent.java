package com.surprising.aeron.service.state;
import com.surprising.aeron.service.exception.CoreStateRejectedException;
import com.surprising.aeron.service.lane.SettlementLaneWorker;
import com.surprising.aeron.protocol.CoreResultCode;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

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
    private long preparedClientKey;
    /** Queued admissions are retained until their command's Matcher continuation consumes them. */
    private boolean matcherWaitRequired;
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
    private volatile boolean matcherConsumed;

    PlaceAdmissionEvent() {
    }

    PlaceAdmissionEvent prepare(long coreSequence, long userId, ResolvedPlaceOrder order, UUID commandId,
                                long openInterestSteps, boolean lifecycleSettled, boolean fundingInProgress,
                                int assetId, int laneId, boolean matcherWaitRequired,
                                TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                long timestamp, long position) {
        if (timestamp < 0 || position < 0 || coreSequence <= 0 || userId <= 0 || order == null || commandId == null || openInterestSteps < 0
                || order.symbolId() < 0 || assetId < 0
                || laneId < 0 || runtime == null) {
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
        this.preparedClientKey = 0;
        this.identities = identities;
        identityAllocations = 0;
        this.assetId = assetId;
        this.laneId = laneId;
        this.matcherWaitRequired = matcherWaitRequired;
        this.runtime = runtime;
        admittedAccountVersion = 0;
        rejection = null;
        reservedAmount = 0;
        completed = false;
        matcherConsumed = false;
        return this;
    }

    void clear() {
        if (!complete()) throw new IllegalStateException("cannot recycle an incomplete place admission");
        if (matcherWaitRequired && !matcherConsumed) {
            throw new IllegalStateException("cannot recycle a place admission before matcher handoff");
        }
        order = null;
        commandId = null;
        lifecycleSettled = false;
        fundingInProgress = false;
        preparedClientKey = 0;
        runtime = null;
        identities = null;
        admittedAccountVersion = 0;
        rejection = null;
        reservedAmount = 0;
        matcherWaitRequired = false;
        matcherConsumed = false;
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
                preparedClientKey = RuntimeIdentityRegistry.clientKeyValue(
                        identities.prepareClientKeyInLane(lane, userId, order.clientOrderId()));
                long positionKey = identities.findPositionKeyValueInLane(
                        lane, userId, order.instrument(), order.positionSide());
                int symbolId = order.symbolId();
                long requiredReservation = RuntimeOrderAdmission.requiredReservationPrepared(
                        runtime, userId, order, openInterestSteps,
                        lane.admissionOrderIndex(symbolId), preparedClientKey, symbolId, positionKey,
                        lifecycleSettled, fundingInProgress);
                reservedAmount = requiredReservation;
                runtime.placeOrderInLane(lane, userId, order, commandId,
                        requiredReservation, preparedClientKey, symbolId, assetId, coreSequence, null, timestamp, position);
                admittedAccountVersion = lane.users.get(userId).revision();
            } finally {
                runtime.exitLaneCommandScope(lane);
            }
        } catch (CoreStateRejectedException | ArithmeticException | IllegalArgumentException failure) {
            identities.rollbackClientKeyInLane(lane, userId, order.clientOrderId(), preparedClientKey);
            preparedClientKey = 0;
            rejection = failure;
        }
        identityAllocations = lane.clientIdentityAllocations - allocationsBefore;
        // Capture every publication dependency before completed becomes visible. The Owner
        // retains queued events until the command's Matcher continuation consumes this payload.
        TradingRuntimeState completionRuntime = runtime;
        completionRuntime.recordAdmissionLaneOperation(lane, System.nanoTime() - startedNanos);
        completed = true;
        completionRuntime.publishPlaceAdmissionReady(laneId, coreSequence);
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
        preparedClientKey = 0;
        runtime = null;
        identities = null;
        admittedAccountVersion = 0;
        rejection = null;
        reservedAmount = 0;
        identityAllocations = 0;
        coreSequence = 0;
        userId = 0;
        laneId = 0;
        matcherWaitRequired = false;
        matcherConsumed = false;
        completed = false;
    }

    /**
     * Matcher waits on this command-owned event instead of a second route-wide FIFO. Commands
     * may be admitted before their shard submission head advances, so a conditional consumer
     * cannot safely share a strict Lane-to-Matcher receipt ring.
     */
    public void awaitMatcherReceipt(MatcherSettlementEvent target) {
        if (!matcherWaitRequired || target == null) {
            throw new IllegalStateException("place admission does not require matcher handoff");
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        int spins = 0;
        while (!completed) {
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("timed out waiting for place admission sequence="
                        + coreSequence + " lane=" + laneId);
            }
            if (spins++ < 1_024) Thread.onSpinWait();
            else {
                LockSupport.parkNanos(this, 1_000L);
                spins = 0;
                if (Thread.currentThread().isInterrupted()) {
                    throw new IllegalStateException("place admission wait was interrupted");
                }
            }
        }
        CoreResultCode resultCode = rejection == null ? CoreResultCode.NONE
                : rejection instanceof CoreStateRejectedException stateRejected
                ? CoreResultCode.fromRejectionCode(stateRejected.code())
                : rejection instanceof ArithmeticException ? CoreResultCode.ARITHMETIC_OVERFLOW
                : CoreResultCode.INVALID_COMMAND;
        target.admissionReceipt(order.orderId(), admittedAccountVersion, reservedAmount,
                rejection == null, resultCode.wireCode());
        // Capture the callback target before publishing consumption. The Owner may observe the
        // volatile flag immediately, clear this pooled event and null runtime before this Matcher
        // thread performs the wake-up. No event field may be read after matcherConsumed becomes
        // visible.
        TradingRuntimeState completionRuntime = runtime;
        matcherConsumed = true;
        completionRuntime.signalOwnerCompletion();
    }

    public boolean matcherConsumed() { return !matcherWaitRequired || matcherConsumed; }

    /** Admission rejection can retire without entering the Matcher queue. */
    public void cancelMatcherWait() {
        if (!complete() || !matcherWaitRequired || matcherConsumed) {
            throw new IllegalStateException("invalid matcher admission cancellation");
        }
        matcherConsumed = true;
    }


    public long takeIdentityAllocations() { long result = identityAllocations; identityAllocations = 0; return result; }
    public long coreSequence() { return coreSequence; }
    public long userId() { return userId; }
    public int laneId() { return laneId; }
    public long orderId() { return order.orderId(); }
    public int assetId() { return assetId; }
    public long clientKey() { return preparedClientKey; }
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
