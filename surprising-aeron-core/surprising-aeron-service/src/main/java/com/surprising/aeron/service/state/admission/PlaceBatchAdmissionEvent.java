package com.surprising.aeron.service.state.admission;

import com.surprising.aeron.service.exception.CoreStateRejectedException;
import com.surprising.aeron.service.lane.SettlementLaneWorker;
import com.surprising.aeron.service.state.TradingRuntimeState;
import com.surprising.aeron.service.state.account.AccountLaneState;
import com.surprising.aeron.service.state.account.LaneBalancePatches;
import com.surprising.aeron.service.state.account.UserRuntime;
import com.surprising.aeron.service.state.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.state.order.OrderRuntime;
import com.surprising.aeron.service.state.settlement.MatcherSettlementChanges;
import java.util.UUID;

/** One-way admission for one user's PLACE batch, owned by exactly one Account Lane. */
public final class PlaceBatchAdmissionEvent implements SettlementLaneWorker.Command {
    private long coreSequence;
    private long timestamp, position;
    private long userId;
    private UUID commandId;
    /** 稳定批次输入由 Owner 交接，只有所属 Lane 解析并冻结。 */
    private PlaceBatchIntentSource source;
    private ResolvedPlaceOrder[] orders;
    private long[] clientKeys;
    private OrderRuntime[] admittedOrders;
    private int itemCount;
    private int admittedCount;
    private int laneId;
    private int matcherShard;
    private TradingRuntimeState runtime;
    private MatcherSettlementChanges changes;
    private RuntimeIdentityRegistry identities;
    private long identityAllocations;
    /** Lane准入已有的不可变用户前值，供该批次回退；不是账户镜像。 */
    private UserRuntime userBefore;
    private UserRuntime userBeforeSlot;
    private RuntimeException rejection;
    /** Volatile publication is sufficient: all payload fields are written before completion. */
    private volatile boolean completed;

    public PlaceBatchAdmissionEvent prepare(
            long coreSequence, long userId, UUID commandId, ResolvedPlaceOrder[] orders,
            long[] clientKeys,
            OrderRuntime[] admittedOrders,
            int itemCount, int laneId, int matcherShard,
            TradingRuntimeState runtime, MatcherSettlementChanges changes, RuntimeIdentityRegistry identities, PlaceBatchIntentSource source, long timestamp, long position) {
        if (timestamp < 0 || position < 0 || coreSequence <= 0 || userId <= 0 || commandId == null || orders == null
                || source == null || identities == null || clientKeys == null || admittedOrders == null || itemCount <= 0
                || itemCount > orders.length || itemCount > clientKeys.length
                || itemCount > admittedOrders.length
                || laneId < 0 || matcherShard < 0
                || runtime == null || matcherShard >= runtime.topology().matchingEngineCount() || changes == null) {
            throw new IllegalArgumentException("invalid place batch admission event");
        }
        this.coreSequence = coreSequence;
        this.timestamp = timestamp;
        this.position = position;
        this.userId = userId;
        this.commandId = commandId;
        this.orders = orders;
        this.source = source;
        this.clientKeys = clientKeys;
        this.admittedOrders = admittedOrders;
        this.itemCount = itemCount;
        this.laneId = laneId;
        this.matcherShard = matcherShard;
        this.runtime = runtime;
        this.changes = changes;
        this.identities = identities;
        identityAllocations = 0;
        admittedCount = 0;
        rejection = null;
        completed = false;
        return this;
    }

    public void execute(AccountLaneState lane) {
        if (lane.laneId() != laneId || laneId != runtime.topology().accountLaneId(userId)) {
            throw new IllegalStateException("place batch admission reached the wrong Account Lane");
        }
        UserRuntime currentUser = lane.user(userId);
        userBefore = currentUser == null ? null
                : (userBeforeSlot = currentUser.copyIntoCommandSlot(userBeforeSlot));
        long allocationsBefore = lane.clientIdentityAllocations();
        try {
            runtime.enterMatcherSettlementScope(lane, changes);
            lane.admissionIndexCapacity(Math.max(2, itemCount));
            try {
                for (int index = 0; index < itemCount; index++) {
                    var decision = source.decision(index);
                    ResolvedPlaceOrder order = CoreOrderDecisionResolver.resolveInto(
                            source.resolvedOrderSlot(index), decision.context(), userId, source.intent(index));
                    orders[index] = order;
                    int symbolId = order.symbolId();
                    int assetId = runtime.productLine().isDerivative() ? decision.settleAssetId()
                            : order.side() == com.surprising.aeron.protocol.CoreOrderSide.BUY
                            ? decision.quoteAssetId() : decision.baseAssetId();
                    long key = RuntimeIdentityRegistry.clientKeyValue(
                            identities.prepareClientKeyInLane(lane, userId, order.clientOrderId()));
                    clientKeys[index] = key;
                    long positionKey = identities.findPositionKeyValueInLane(
                            lane, userId, order.instrument(), order.positionSide());
                    if (runtime.productLine().isDerivative() && positionKey != 0) {
                        var position = lane.position(positionKey);
                        if (position != null && position.signedQuantitySteps() != 0
                                && (position.signedQuantitySteps() > 0) != (order.side() == com.surprising.aeron.protocol.CoreOrderSide.BUY)) {
                            var summary = lane.admissionOrderIndex(symbolId).inspect(userId, order.instrumentId(),
                                    order.positionSide(), order.side(), order.marginMode());
                            if (summary.reduceOnlyQuantity() > 0 && Math.addExact(Math.addExact(summary.pendingQuantity(), summary.reduceOnlyQuantity()), order.quantitySteps())
                                    > Math.absExact(position.signedQuantitySteps()))
                                throw new CoreStateRejectedException("CLOSE_CAPACITY_REQUIRES_ORDERED_ADMISSION", "close commitments require ordered cancellation");
                        }
                    }
                    long requiredReservation = RuntimeOrderAdmission.requiredReservationPrepared(
                            runtime, lane, userId, order, decision.openInterestSteps(),
                            lane.admissionOrderIndex(symbolId), key, symbolId, positionKey,
                            decision.context().lifecycleSettled(), decision.context().fundingInProgress());
                    runtime.placeOrderInLane(lane, userId, order, commandId,
                            requiredReservation, clientKeys[index], symbolId, assetId,
                            coreSequence, null, timestamp, position);
                    OrderRuntime live = lane.order(order.orderId());
                    admittedOrders[index] = source.captureAdmittedOrder(index, live);
                    admittedCount++;
                }
                changes.prepareAdmissionLane(laneId, runtime);
            } finally {
                lane.admissionIndexCapacity(2);
                runtime.exitMatcherSettlementScope(lane, changes);
            }
        } catch (CoreStateRejectedException | ArithmeticException | IllegalArgumentException failure) {
            runtime.rollbackPlaceBatchAdmissionInLane(
                    lane, userId, coreSequence, orders, clientKeys, admittedCount, userBefore);
            for (int i = 0; i < itemCount; i++) {
                if (orders[i] != null) identities.rollbackClientKeyInLane(lane, userId, orders[i].clientOrderId(), clientKeys[i]);
                clientKeys[i] = 0;
            }
            admittedCount = 0;
            rejection = failure;
        }
        identityAllocations = lane.clientIdentityAllocations() - allocationsBefore;
        TradingRuntimeState completionRuntime = runtime;
        lane.recordCommandOperation();
        completed = true;
        completionRuntime.publishAccountAdmissionReadyShard(matcherShard);
    }

    public void clear() {
        if (!complete() || changes != null) {
            throw new IllegalStateException("cannot recycle an incomplete place batch admission");
        }
        source = null;
        orders = null;
        clientKeys = null;
        admittedOrders = null;
        userBefore = null;
        if (userBeforeSlot != null) userBeforeSlot.clearCommandSlot();
        runtime = null;
        identities = null;
        rejection = null;
        itemCount = 0;
        admittedCount = 0;
        matcherShard = 0;
    }

    public long takeIdentityAllocations() { long count = identityAllocations; identityAllocations = 0; return count; }
    public boolean complete() { return completed; }
    public RuntimeException rejection() {
        if (!complete()) throw new IllegalStateException("place batch admission is incomplete");
        return rejection;
    }
    public UserRuntime userBefore() {
        if (!complete()) throw new IllegalStateException("place batch admission is incomplete");
        return userBefore;
    }
    public long coreSequence() { return coreSequence; }
    public long userId() { return userId; }
    public OrderRuntime[] admittedOrders() { return admittedOrders; }
    public int itemCount() { return itemCount; }
    OrderRuntime admittedOrder(int index) { return admittedOrders[index]; }
    ResolvedPlaceOrder order(int index) { return orders[index]; }
    public MatcherSettlementChanges takeChanges() {
        if (!complete() || changes == null) {
            throw new IllegalStateException("place batch admission changes are unavailable");
        }
        MatcherSettlementChanges value = changes;
        changes = null;
        return value;
    }

    public void copyBalanceBeforeTo(LaneBalancePatches target) {
        if (!complete() || changes == null || target == null) {
            throw new IllegalStateException("place batch admission changes are unavailable");
        }
        changes.copyBalanceBeforeTo(target);
    }
    public MatcherSettlementChanges discardChanges() {
        MatcherSettlementChanges value = changes;
        changes = null;
        completed = true;
        return value;
    }
}
