package com.surprising.aeron.service.state;

import com.surprising.aeron.service.matching.CoreMatchingOrder;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.UUID;

/** One-way admission for one user's PLACE batch, owned by exactly one Account Lane. */
public final class PlaceBatchAdmissionEvent implements SettlementLaneWorker.Command {
    private static final VarHandle COMPLETED;

    static {
        try {
            COMPLETED = MethodHandles.lookup().findVarHandle(
                    PlaceBatchAdmissionEvent.class, "completed", boolean.class);
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private long coreSequence;
    private long userId;
    private UUID commandId;
    private ResolvedPlaceOrder[] orders;
    private long[] requiredReservations;
    private RuntimeIdentityRegistry.PreparedClientKey[] clientKeys;
    private int[] symbolIds;
    private int[] assetIds;
    private CoreMatchingOrder[] matchingOrders;
    private OrderRuntime[] admittedOrders;
    private ReservationRuntime[] admittedReservations;
    private int itemCount;
    private int admittedCount;
    private int laneId;
    private TradingRuntimeState runtime;
    private UserRuntime admittedUser;
    private RuntimeException rejection;
    @SuppressWarnings("FieldMayBeFinal")
    private boolean completed;

    PlaceBatchAdmissionEvent prepare(
            long coreSequence, long userId, UUID commandId, ResolvedPlaceOrder[] orders,
            long[] requiredReservations,
            RuntimeIdentityRegistry.PreparedClientKey[] clientKeys,
            int[] symbolIds, int[] assetIds,
            CoreMatchingOrder[] matchingOrders, OrderRuntime[] admittedOrders,
            ReservationRuntime[] admittedReservations, int itemCount, int laneId,
            TradingRuntimeState runtime) {
        if (coreSequence <= 0 || userId <= 0 || commandId == null || orders == null
                || requiredReservations == null || clientKeys == null || symbolIds == null
                || assetIds == null || matchingOrders == null || admittedOrders == null
                || admittedReservations == null || itemCount <= 0
                || itemCount > orders.length || itemCount > requiredReservations.length
                || itemCount > clientKeys.length || itemCount > symbolIds.length || itemCount > assetIds.length
                || itemCount > matchingOrders.length || itemCount > admittedOrders.length
                || itemCount > admittedReservations.length || laneId < 0 || runtime == null) {
            throw new IllegalArgumentException("invalid place batch admission event");
        }
        this.coreSequence = coreSequence;
        this.userId = userId;
        this.commandId = commandId;
        this.orders = orders;
        this.requiredReservations = requiredReservations;
        this.clientKeys = clientKeys;
        this.symbolIds = symbolIds;
        this.assetIds = assetIds;
        this.matchingOrders = matchingOrders;
        this.admittedOrders = admittedOrders;
        this.admittedReservations = admittedReservations;
        this.itemCount = itemCount;
        this.laneId = laneId;
        this.runtime = runtime;
        admittedCount = 0;
        admittedUser = null;
        rejection = null;
        COMPLETED.set(this, false);
        return this;
    }

    @Override
    public void execute(AccountLaneState lane) {
        long startedNanos = System.nanoTime();
        if (lane.laneId() != laneId || laneId != runtime.topology().accountLaneId(userId)) {
            throw new IllegalStateException("place batch admission reached the wrong Account Lane");
        }
        UserRuntime userBefore = lane.users.get(userId);
        try {
            runtime.enterLaneCommandScope(lane);
            try {
                for (int index = 0; index < itemCount; index++) {
                    ResolvedPlaceOrder order = orders[index];
                    RuntimeCommandProcessor.placeOrderPreparedInLane(runtime, lane, userId, order, commandId,
                            requiredReservations[index], clientKeys[index].key(), symbolIds[index], assetIds[index],
                            coreSequence);
                    admittedOrders[index] = lane.orders.get(order.orderId());
                    admittedReservations[index] = lane.reservations.get(order.orderId());
                    admittedCount++;
                }
                admittedUser = lane.users.get(userId);
            } finally {
                runtime.exitLaneCommandScope(lane);
            }
        } catch (CoreStateRejectedException | ArithmeticException | IllegalArgumentException failure) {
            runtime.rollbackPlaceBatchAdmissionInLane(lane, userId, coreSequence, orders, clientKeys,
                    admittedReservations, admittedCount, userBefore);
            admittedCount = 0;
            rejection = failure;
        }
        TradingRuntimeState completionRuntime = runtime;
        int completionLaneId = laneId;
        long completionSequence = coreSequence;
        completionRuntime.recordAdmissionLaneOperation(lane, System.nanoTime() - startedNanos);
        COMPLETED.setRelease(this, true);
        completionRuntime.publishPlaceAdmissionReady(completionLaneId, completionSequence);
    }

    void clear() {
        if (!complete()) throw new IllegalStateException("cannot recycle an incomplete place batch admission");
        orders = null;
        requiredReservations = null;
        clientKeys = null;
        symbolIds = null;
        assetIds = null;
        matchingOrders = null;
        admittedOrders = null;
        admittedReservations = null;
        runtime = null;
        admittedUser = null;
        rejection = null;
        itemCount = 0;
        admittedCount = 0;
    }

    public boolean complete() { return (boolean) COMPLETED.getAcquire(this); }
    public RuntimeException rejection() {
        if (!complete()) throw new IllegalStateException("place batch admission is incomplete");
        return rejection;
    }
    long coreSequence() { return coreSequence; }
    long userId() { return userId; }
    int itemCount() { return itemCount; }
    UserRuntime admittedUser() { return admittedUser; }
    OrderRuntime admittedOrder(int index) { return admittedOrders[index]; }
    ReservationRuntime admittedReservation(int index) { return admittedReservations[index]; }
    ResolvedPlaceOrder order(int index) { return orders[index]; }
}
