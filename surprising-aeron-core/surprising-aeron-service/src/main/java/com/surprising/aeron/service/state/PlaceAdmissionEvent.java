package com.surprising.aeron.service.state;

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
    private long userId;
    private ResolvedPlaceOrder order;
    private UUID commandId;
    private long openInterestSteps;
    private RuntimeOrderAdmission.AdmissionIdentity identity;
    private RuntimeIdentityRegistry.PreparedClientKey preparedClientKey;
    private int symbolId;
    private int assetId;
    private int laneId;
    private TradingRuntimeState runtime;
    private CoreMatchingOrder matchingOrder;
    private UserRuntime admittedUser;
    private OrderRuntime admittedOrder;
    private ReservationRuntime admittedReservation;
    private RuntimeException rejection;
    @SuppressWarnings("FieldMayBeFinal")
    private boolean completed;

    PlaceAdmissionEvent() {
    }

    PlaceAdmissionEvent prepare(long coreSequence, long userId, ResolvedPlaceOrder order, UUID commandId,
                                long openInterestSteps, RuntimeOrderAdmission.AdmissionIdentity identity,
                                RuntimeIdentityRegistry.PreparedClientKey preparedClientKey,
                                int symbolId, int assetId, int laneId, TradingRuntimeState runtime) {
        if (coreSequence <= 0 || userId <= 0 || order == null || commandId == null || openInterestSteps < 0
                || identity == null || preparedClientKey == null || symbolId < 0 || assetId < 0
                || laneId < 0 || runtime == null) {
            throw new IllegalArgumentException("invalid place admission event");
        }
        this.coreSequence = coreSequence;
        this.userId = userId;
        this.order = order;
        this.commandId = commandId;
        this.openInterestSteps = openInterestSteps;
        this.identity = identity;
        this.preparedClientKey = preparedClientKey;
        this.symbolId = symbolId;
        this.assetId = assetId;
        this.laneId = laneId;
        this.runtime = runtime;
        matchingOrder = null;
        admittedUser = null;
        admittedOrder = null;
        admittedReservation = null;
        rejection = null;
        COMPLETED.set(this, false);
        return this;
    }

    void clear() {
        if (!complete()) throw new IllegalStateException("cannot recycle an incomplete place admission");
        order = null;
        commandId = null;
        identity = null;
        preparedClientKey = null;
        runtime = null;
        matchingOrder = null;
        admittedUser = null;
        admittedOrder = null;
        admittedReservation = null;
        rejection = null;
    }

    @Override
    public void execute(AccountLaneState lane) {
        long startedNanos = System.nanoTime();
        if (lane.laneId() != runtime.topology().accountLaneId(userId)) {
            throw new IllegalStateException("place admission reached the wrong Account Lane");
        }
        try {
            runtime.enterLaneCommandScope(lane);
            try {
                long requiredReservation = RuntimeOrderAdmission.requiredReservationPrepared(
                        runtime, userId, order, openInterestSteps,
                        lane.admissionOrderIndex(symbolId), identity);
                RuntimeCommandProcessor.placeOrderPreparedInLane(runtime, lane, userId, order, commandId,
                        requiredReservation, preparedClientKey.key(), symbolId, assetId, coreSequence);
                admittedUser = lane.users.get(userId);
                admittedOrder = lane.orders.get(order.orderId());
                admittedReservation = lane.reservations.get(order.orderId());
                matchingOrder = new CoreMatchingOrder(order.orderId(), order.symbol(), order.side(),
                        order.orderType(), order.timeInForce(), order.matchingPriceTicks(), order.quantitySteps());
            } finally {
                runtime.exitLaneCommandScope(lane);
            }
        } catch (CoreStateRejectedException | ArithmeticException | IllegalArgumentException failure) {
            rejection = failure;
        }
        // Capture every publication dependency before completed becomes visible to the owner,
        // which is then allowed to clear and recycle this event immediately.
        TradingRuntimeState completionRuntime = runtime;
        int completionLaneId = laneId;
        long completionSequence = coreSequence;
        completionRuntime.recordAdmissionLaneOperation(lane, System.nanoTime() - startedNanos);
        COMPLETED.setRelease(this, true);
        completionRuntime.publishPlaceAdmissionReady(completionLaneId, completionSequence);
    }

    public boolean complete() {
        return (boolean) COMPLETED.getAcquire(this);
    }


    public long coreSequence() { return coreSequence; }
    public long userId() { return userId; }
    public long orderId() { return order.orderId(); }
    public int assetId() { return assetId; }
    public long clientKey() { return preparedClientKey.key(); }
    public boolean allocatedClientKey() { return preparedClientKey.allocated(); }
    public String clientOrderId() { return order.clientOrderId(); }
    public CoreMatchingOrder matchingOrder() {
        if (!complete() || matchingOrder == null) throw new IllegalStateException("place admission is not accepted");
        return matchingOrder;
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
