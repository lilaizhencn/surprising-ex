package com.surprising.aeron.service.state;
import com.surprising.aeron.service.lane.SettlementLaneWorker;
import com.surprising.aeron.service.matching.CoreMatchingOrder;
import java.util.UUID;

/** One-way admission for one user's PLACE batch, owned by exactly one Account Lane. */
public final class PlaceBatchAdmissionEvent implements SettlementLaneWorker.Command {
    private long coreSequence;
    private long timestamp, position;
    private long userId;
    private UUID commandId;
    /** 有值时由 Lane 解析意图；直接状态调用可传入已解析订单。 */
    private PlaceBatchIntentSource source;
    private ResolvedPlaceOrder[] orders;
    private long[] openInterestSteps;
    private boolean[] lifecycleSettled;
    private boolean[] fundingInProgress;
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
    private TradingRuntimeState.MatcherSettlementChanges changes;
    private RuntimeIdentityRegistry identities;
    private long identityAllocations;
    private UserRuntime admittedUser;
    /** Retained with this pooled event; cleared by Owner before reuse by the Lane. */
    private LanePublication publication;
    private RuntimeException rejection;
    /** Volatile publication is sufficient: all payload fields are written before completion. */
    private volatile boolean completed;

    PlaceBatchAdmissionEvent prepare(
            long coreSequence, long userId, UUID commandId, ResolvedPlaceOrder[] orders,
            long[] openInterestSteps, boolean[] lifecycleSettled, boolean[] fundingInProgress,
            RuntimeIdentityRegistry.PreparedClientKey[] clientKeys,
            int[] symbolIds, int[] assetIds,
            CoreMatchingOrder[] matchingOrders, OrderRuntime[] admittedOrders,
            ReservationRuntime[] admittedReservations, int itemCount, int laneId,
            TradingRuntimeState runtime, TradingRuntimeState.MatcherSettlementChanges changes, RuntimeIdentityRegistry identities, PlaceBatchIntentSource source, long timestamp, long position) {
        if (timestamp < 0 || position < 0 || coreSequence <= 0 || userId <= 0 || commandId == null || orders == null
                || openInterestSteps == null || lifecycleSettled == null || fundingInProgress == null
                || clientKeys == null || symbolIds == null
                || assetIds == null || matchingOrders == null || admittedOrders == null
                || admittedReservations == null || itemCount <= 0
                || itemCount > orders.length || itemCount > openInterestSteps.length
                || itemCount > lifecycleSettled.length || itemCount > fundingInProgress.length
                || itemCount > clientKeys.length || itemCount > symbolIds.length || itemCount > assetIds.length
                || itemCount > matchingOrders.length || itemCount > admittedOrders.length
                || itemCount > admittedReservations.length || laneId < 0 || runtime == null || changes == null) {
            throw new IllegalArgumentException("invalid place batch admission event");
        }
        this.coreSequence = coreSequence;
        this.timestamp = timestamp;
        this.position = position;
        this.userId = userId;
        this.commandId = commandId;
        this.orders = orders;
        this.source = source;
        this.openInterestSteps = openInterestSteps;
        this.lifecycleSettled = lifecycleSettled;
        this.fundingInProgress = fundingInProgress;
        this.clientKeys = clientKeys;
        this.symbolIds = symbolIds;
        this.assetIds = assetIds;
        this.matchingOrders = matchingOrders;
        this.admittedOrders = admittedOrders;
        this.admittedReservations = admittedReservations;
        this.itemCount = itemCount;
        this.laneId = laneId;
        this.runtime = runtime;
        this.changes = changes;
        this.identities = identities;
        identityAllocations = 0;
        admittedCount = 0;
        admittedUser = null;
        if (publication != null) publication.clear();
        rejection = null;
        completed = false;
        runtime.expectPlaceAdmission(laneId);
        return this;
    }

    @Override
    public void execute(AccountLaneState lane) {
        long startedNanos = System.nanoTime();
        if (lane.laneId() != laneId || laneId != runtime.topology().accountLaneId(userId)) {
            throw new IllegalStateException("place batch admission reached the wrong Account Lane");
        }
        UserRuntime userBefore = lane.users.get(userId);
        long allocationsBefore = lane.clientIdentityAllocations;
        try {
            runtime.enterMatcherSettlementScope(lane, changes);
            lane.admissionIndexCapacity = Math.max(2, itemCount);
            try {
                for (int index = 0; index < itemCount; index++) {
                    if (source != null) {
                        var decision = source.decision(index);
                        var resolved = CoreOrderDecisionResolver.resolve(decision.context(), source.intent(index));
                        orders[index] = resolved;
                        symbolIds[index] = resolved.symbolId();
                        assetIds[index] = runtime.productLine().isDerivative() ? decision.settleAssetId()
                                : resolved.side() == com.surprising.aeron.protocol.CoreOrderSide.BUY
                                ? decision.quoteAssetId() : decision.baseAssetId();
                        lifecycleSettled[index] = decision.context().lifecycleSettled();
                        fundingInProgress[index] = decision.context().fundingInProgress();
                        openInterestSteps[index] = decision.openInterestSteps();
                        matchingOrders[index] = new CoreMatchingOrder(resolved.orderId(), resolved.symbol(), resolved.side(),
                                resolved.orderType(), resolved.timeInForce(), resolved.matchingPriceTicks(), resolved.quantitySteps());
                    }
                    ResolvedPlaceOrder order = orders[index];
                    var key = identities.prepareClientKeyInLane(lane, userId, order.clientOrderId());
                    clientKeys[index] = key;
                    String positionIdentity = order.positionSide() == com.surprising.aeron.protocol.CorePositionSide.NET
                            ? order.symbol() : order.symbol() + ':' + order.positionSide().name();
                    long positionKey = identities.findPositionKeyValueInLane(lane, userId, positionIdentity);
                    if (source != null && runtime.productLine().isDerivative() && positionKey != 0) {
                        var position = lane.positions.get(positionKey);
                        if (position != null && position.signedQuantitySteps() != 0
                                && (position.signedQuantitySteps() > 0) != (order.side() == com.surprising.aeron.protocol.CoreOrderSide.BUY)) {
                            var summary = lane.admissionOrderIndex(symbolIds[index]).inspect(userId, order.symbol(),
                                    order.positionSide(), order.side(), order.marginMode());
                            if (summary.reduceOnlyQuantity() > 0 && Math.addExact(Math.addExact(summary.pendingQuantity(), summary.reduceOnlyQuantity()), order.quantitySteps())
                                    > Math.absExact(position.signedQuantitySteps()))
                                throw new CoreStateRejectedException("CLOSE_CAPACITY_REQUIRES_ORDERED_ADMISSION", "close commitments require ordered cancellation");
                        }
                    }
                    long requiredReservation = RuntimeOrderAdmission.requiredReservationPrepared(
                            runtime, userId, order, openInterestSteps[index],
                            lane.admissionOrderIndex(symbolIds[index]), key.key(), symbolIds[index], positionKey,
                            lifecycleSettled[index], fundingInProgress[index]);
                    runtime.placeOrderInLane(lane, userId, order, commandId,
                            requiredReservation, clientKeys[index].key(), symbolIds[index], assetIds[index],
                            coreSequence, null, timestamp, position);
                    admittedOrders[index] = lane.orders.get(order.orderId());
                    admittedReservations[index] = lane.reservations.get(order.orderId());
                    admittedCount++;
                }
                admittedUser = lane.users.get(userId);
                changes.prepareAdmissionLane(laneId, runtime);
                if (publication == null) publication = new LanePublication();
                runtime.publishedUsers.stage(publication, userId, admittedUser);
                for (int i = 0; i < itemCount; i++) {
                    runtime.publishedOrders.stage(publication, admittedOrders[i].orderId(), admittedOrders[i]);
                    runtime.publishedReservations.stage(publication, admittedReservations[i].orderId(), admittedReservations[i]);
                }
            } finally {
                lane.admissionIndexCapacity = 2;
                runtime.exitMatcherSettlementScope(lane, changes);
            }
        } catch (CoreStateRejectedException | ArithmeticException | IllegalArgumentException failure) {
            runtime.rollbackPlaceBatchAdmissionInLane(lane, userId, coreSequence, orders, clientKeys,
                    admittedReservations, admittedCount, userBefore);
            for (int i = 0; i < itemCount; i++) {
                if (orders[i] != null) identities.rollbackClientKeyInLane(lane, userId, orders[i].clientOrderId(), clientKeys[i]);
                clientKeys[i] = null;
            }
            admittedCount = 0;
            rejection = failure;
        }
        identityAllocations = lane.clientIdentityAllocations - allocationsBefore;
        TradingRuntimeState completionRuntime = runtime;
        int completionLaneId = laneId;
        long completionSequence = coreSequence;
        completionRuntime.recordAdmissionLaneOperation(lane, System.nanoTime() - startedNanos);
        completed = true;
        completionRuntime.publishPlaceAdmissionReady(completionLaneId, completionSequence);
    }

    void clear() {
        if (!complete() || changes != null) {
            throw new IllegalStateException("cannot recycle an incomplete place batch admission");
        }
        source = null;
        orders = null;
        openInterestSteps = null;
        lifecycleSettled = null;
        fundingInProgress = null;
        clientKeys = null;
        symbolIds = null;
        assetIds = null;
        matchingOrders = null;
        admittedOrders = null;
        admittedReservations = null;
        runtime = null;
        identities = null;
        admittedUser = null;
        if (publication != null) publication.clear();
        rejection = null;
        itemCount = 0;
        admittedCount = 0;
    }

    public long takeIdentityAllocations() { long count = identityAllocations; identityAllocations = 0; return count; }
    public boolean complete() { return completed; }
    public RuntimeException rejection() {
        if (!complete()) throw new IllegalStateException("place batch admission is incomplete");
        return rejection;
    }
    LanePublication publication() { return publication; }
    public long coreSequence() { return coreSequence; }
    long userId() { return userId; }
    OrderRuntime[] admittedOrders() { return admittedOrders; }
    int itemCount() { return itemCount; }
    UserRuntime admittedUser() { return admittedUser; }
    OrderRuntime admittedOrder(int index) { return admittedOrders[index]; }
    ReservationRuntime admittedReservation(int index) { return admittedReservations[index]; }
    ResolvedPlaceOrder order(int index) { return orders[index]; }
    TradingRuntimeState.MatcherSettlementChanges takeChanges() {
        if (!complete() || changes == null) {
            throw new IllegalStateException("place batch admission changes are unavailable");
        }
        TradingRuntimeState.MatcherSettlementChanges value = changes;
        changes = null;
        return value;
    }

    void copyBalanceBeforeTo(TradingRuntimeState.LaneBalancePatches target) {
        if (!complete() || changes == null || target == null) {
            throw new IllegalStateException("place batch admission changes are unavailable");
        }
        changes.copyBalanceBeforeTo(target);
    }
    TradingRuntimeState.MatcherSettlementChanges discardChanges() {
        if (!complete()) runtime.releaseAdmissionExpectation(laneId);
        TradingRuntimeState.MatcherSettlementChanges value = changes;
        changes = null;
        completed = true;
        return value;
    }
}
