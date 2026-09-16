package com.surprising.aeron.service.state;
import com.surprising.aeron.service.lane.SettlementLaneWorker;
import com.surprising.aeron.service.matching.CoreMatchingOrder;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.UUID;

/** One-way place admission owned and completed by exactly one Account Lane. */
public final class PlaceAdmissionEvent implements SettlementLaneWorker.Command {
    private static final VarHandle COMPLETED;

    static {
        try {
            COMPLETED = MethodHandles.lookup().findVarHandle(PlaceAdmissionEvent.class, "completed", boolean.class);
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

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
    private int assetId;
    private int laneId;
    private TradingRuntimeState runtime;
    private RuntimeIdentityRegistry identities;
    private LanePublication publication;
    private LanePublication publicationBuffer;
    private long identityAllocations;
    private UserRuntime admittedUser;
    private OrderRuntime admittedOrder;
    private ReservationRuntime admittedReservation;
    private RuntimeException rejection;
    /** Direct Matcher settlement queued behind this admission; only one normal PLACE uses it. */
    private MatcherSettlementEvent dependentSettlement;
    @SuppressWarnings("FieldMayBeFinal")
    private boolean completed;

    PlaceAdmissionEvent() {
    }

    PlaceAdmissionEvent prepare(long coreSequence, long userId, ResolvedPlaceOrder order, UUID commandId,
                                long openInterestSteps, boolean lifecycleSettled, boolean fundingInProgress,
                                int symbolId, int assetId, int laneId, TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                long timestamp, long position) {
        if (timestamp < 0 || position < 0 || coreSequence <= 0 || userId <= 0 || order == null || commandId == null || openInterestSteps < 0
                || symbolId < 0 || assetId < 0
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
        this.preparedClientKey = null;
        this.identities = identities;
        if (publication != null) publication.clear();
        publication = null;
        identityAllocations = 0;
        this.symbolId = symbolId;
        this.assetId = assetId;
        this.laneId = laneId;
        this.runtime = runtime;
        admittedUser = null;
        admittedOrder = null;
        admittedReservation = null;
        rejection = null;
        dependentSettlement = null;
        COMPLETED.set(this, false);
        runtime.expectPlaceAdmission(laneId);
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
        if (publication != null) publication.clear();
        publication = null;
        admittedUser = null;
        admittedOrder = null;
        admittedReservation = null;
        rejection = null;
        dependentSettlement = null;
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
                runtime.placeOrderInLane(lane, userId, order, commandId,
                        requiredReservation, preparedClientKey.key(), symbolId, assetId, coreSequence, null, timestamp, position);
                admittedUser = lane.users.get(userId);
                admittedOrder = lane.orders.get(order.orderId());
                admittedReservation = lane.reservations.get(order.orderId());
                if (publicationBuffer == null) publicationBuffer = new LanePublication();
                publication = publicationBuffer;
                runtime.publishedUsers.stage(publication, userId, admittedUser);
                runtime.publishedOrders.stage(publication, order.orderId(), admittedOrder);
                runtime.publishedReservations.stage(publication, order.orderId(), admittedReservation);
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
        MatcherSettlementEvent dependent = dependentSettlement;
        completionRuntime.recordAdmissionLaneOperation(lane, System.nanoTime() - startedNanos);
        COMPLETED.setRelease(this, true);
        completionRuntime.publishPlaceAdmissionReady(completionLaneId, completionSequence);
        if (dependent != null) dependent.signalAdmissionReady();
    }

    public boolean complete() {
        return (boolean) COMPLETED.getAcquire(this);
    }

    /** Abort a prepared event that was never submitted to a Lane. */
    void discard() {
        if (complete()) throw new IllegalStateException("completed place admission must be collected");
        if (runtime != null) runtime.releaseAdmissionExpectation(laneId);
        order = null;
        commandId = null;
        lifecycleSettled = false;
        fundingInProgress = false;
        preparedClientKey = null;
        runtime = null;
        identities = null;
        if (publication != null) publication.clear();
        publication = null;
        admittedUser = null;
        admittedOrder = null;
        admittedReservation = null;
        rejection = null;
        dependentSettlement = null;
        identityAllocations = 0;
        coreSequence = 0;
        userId = 0;
        laneId = 0;
        COMPLETED.setRelease(this, false);
    }


    LanePublication publication() { return publication; }
    public long takeIdentityAllocations() { long result = identityAllocations; identityAllocations = 0; return result; }
    public long coreSequence() { return coreSequence; }
    public long userId() { return userId; }
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
    public CoreMatchingOrder matchingOrder() {
        ResolvedPlaceOrder resolved = order;
        if (resolved == null) throw new IllegalStateException("place admission is not prepared");
        return new CoreMatchingOrder(resolved.orderId(), resolved.symbol(), resolved.side(),
                resolved.orderType(), resolved.timeInForce(), resolved.matchingPriceTicks(),
                resolved.quantitySteps());
    }

    /** Owner-only immutable input used to preconstruct a direct settlement before Lane admission. */
    ResolvedPlaceOrder preparedOrder() {
        if (order == null) throw new IllegalStateException("place admission is not prepared");
        return order;
    }

    /** Binds the preconstructed Matcher result to this admission without another callback object. */
    void dependentSettlement(MatcherSettlementEvent event) {
        if (event == null || dependentSettlement != null)
            throw new IllegalStateException("invalid place admission dependency");
        dependentSettlement = event;
    }
    UserRuntime admittedUser() {
        if (!complete() || admittedUser == null) throw new IllegalStateException("place admission is incomplete");
        return admittedUser;
    }
    OrderRuntime admittedOrder() {
        if (!complete() || admittedOrder == null) throw new IllegalStateException("place admission is incomplete");
        return admittedOrder;
    }
    ReservationRuntime admittedReservation() {
        if (!complete() || admittedReservation == null) {
            throw new IllegalStateException("place admission is incomplete");
        }
        return admittedReservation;
    }
    public RuntimeException rejection() {
        if (!complete()) throw new IllegalStateException("place admission is incomplete");
        return rejection;
    }
}
