package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.service.state.admission.CoreOrderDecisionResolver;

import java.util.List;
import java.util.UUID;
import java.util.function.IntFunction;

/** 一个顺序批量项的 Lane 准入和冻结；批次槽复用，完成回执不复制账户状态。 */
public final class BatchPlaceReservation extends ResolvedPlaceOrder implements IntFunction<Object> {
    private TradingRuntimeState runtime;
    private RuntimeIdentityRegistry identities;
    private long sequence, userId, openInterestSteps;
    private UUID commandId;
    private int assetId;
    private boolean lifecycleSettled, fundingInProgress;
    private long preparedClientKey;
    private OrderRuntime admittedOrder;
    private List<Long> closeCapacityCancellations = List.of();
    private volatile boolean completed = true;

    public void prepare(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
            long sequence, long userId, UUID commandId, PlaceOrderCommand command,
            CoreOrderDecisionResolver.Context decision, long openInterestSteps) {
        if (runtime == null || identities == null || sequence <= 0 || userId <= 0
                || commandId == null || command == null || decision == null || openInterestSteps < 0)
            throw new IllegalArgumentException("invalid batch place reservation");
        runtime.assertOwner();
        if (!completed) throw new IllegalStateException("Account Lane still owns this batch reservation");
        clear();
        CoreOrderDecisionResolver.resolveInto(this, decision, command);
        this.runtime = runtime;
        this.identities = identities;
        this.sequence = sequence;
        this.userId = userId;
        this.commandId = commandId;
        this.openInterestSteps = openInterestSteps;
        assetId = identities.assetId(reservationAsset());
        lifecycleSettled = decision.lifecycleSettled();
        fundingInProgress = decision.fundingInProgress();
        completed = false;
    }

    @Override public Object apply(int laneId) {
        AccountLaneState lane = runtime.currentLaneCommandScope();
        if (lane == null || lane.laneId() != laneId || laneId != runtime.topology().accountLaneId(userId))
            throw new IllegalStateException("batch place reservation requires its Account Lane");
        long identity = 0;
        try {
            identity = identities.prepareClientKeyInLane(lane, userId, clientOrderId());
            preparedClientKey = identity;
            long clientKey = RuntimeIdentityRegistry.clientKeyValue(identity);
            long positionKey = identities.findPositionKeyValueInLane(lane, userId, instrument(), positionSide());
            long required = RuntimeOrderAdmission.requiredReservationPrepared(runtime, userId, this,
                    openInterestSteps, lane.admissionOrderIndex(symbolId()), clientKey, symbolId(), positionKey,
                    lifecycleSettled, fundingInProgress);
            PositionRuntime position = positionKey == 0 ? null : lane.positions.get(positionKey);
            if (instrument().contractType().productLine().isDerivative() && !reduceOnly()
                    && position != null && position.signedQuantitySteps() != 0
                    && (position.signedQuantitySteps() > 0) != (side() == CoreOrderSide.BUY)) {
                closeCapacityCancellations = PositionCloseCapacity.inspectLane(lane, userId, symbolId(),
                        positionKey, positionSide(), side(), orderId()).conflictsFor(quantitySteps());
            }
            RuntimeOrderStateTransitions.reserveBatchOrderInLane(runtime, userId, this, commandId,
                    required, clientKey, assetId, sequence);
            // reserveOrder already published the immutable admission value. Retain
            // that receipt, never the mutable order being settled by this Lane.
            admittedOrder = runtime.laneDelta(laneId).orders.get(orderId());
            if (admittedOrder == null) throw new IllegalStateException("batch admission order is missing");
            return null;
        } catch (RuntimeException | Error failure) {
            if (identity != 0) identities.rollbackClientKeyInLane(lane, userId, clientOrderId(), identity);
            preparedClientKey = 0;
            throw failure;
        } finally {
            // No task fields may be read after release. The existing control dispatcher
            // still owns result publication; Owner only consumes after pollControlLanes.
            completed = true;
        }
    }

    /** 只在既有 ControlLaneDispatcher 完成发布后由 Owner 消费以下回执。 */
    public OrderRuntime admittedOrder() {
        if (admittedOrder == null) throw new IllegalStateException("batch reservation has not completed");
        return admittedOrder;
    }
    public long preparedClientKey() { return preparedClientKey; }
    public List<Long> closeCapacityCancellations() { return closeCapacityCancellations; }

    public void clear() {
        // Fatal teardown may retire the batch while its control task is still queued.
        // Retain that task's input; prepare() prevents reuse until the Lane releases it.
        if (!completed) return;
        clearResolvedOrder();
        runtime = null;
        identities = null;
        commandId = null;
        sequence = userId = openInterestSteps = preparedClientKey = 0;
        assetId = 0;
        lifecycleSettled = fundingInProgress = false;
        admittedOrder = null;
        closeCapacityCancellations = List.of();
    }
}
