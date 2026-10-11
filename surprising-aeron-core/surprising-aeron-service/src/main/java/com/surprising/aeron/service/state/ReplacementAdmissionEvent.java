package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.AmendOrderCommand;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ReplaceOrderCommand;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.service.command.order.ResolvedMatchingAdmission;
import com.surprising.aeron.service.exception.CoreStateRejectedException;
import com.surprising.aeron.service.lane.SettlementLaneWorker;
import com.surprising.aeron.service.state.admission.CoreOrderDecisionResolver;
import com.surprising.aeron.service.state.model.CoreOrderStatus;

import java.util.List;

/** The owning Account Lane validates a replacement; the command retains its resolved input through terminal. */
public final class ReplacementAdmissionEvent extends ResolvedPlaceOrder implements SettlementLaneWorker.Command {
    private long sequence, userId, originalOrderId, openInterestSteps;
    private int matcherShard;
    private AmendOrderCommand amend;
    private ReplaceOrderCommand replace;
    private CoreOrderDecisionResolver.Context decision;
    private TradingRuntimeState runtime;
    private RuntimeIdentityRegistry identities;
    private ResolvedMatchingAdmission admission;
    private List<Long> closeCapacityCancellations = List.of();
    private RuntimeException rejection;
    private volatile boolean completed;

    ReplacementAdmissionEvent prepare(long sequence, long userId, long originalOrderId,
            AmendOrderCommand amend, ReplaceOrderCommand replace,
            CoreOrderDecisionResolver.Context decision, long openInterestSteps, int matcherShard,
            TradingRuntimeState runtime, RuntimeIdentityRegistry identities) {
        if (sequence <= 0 || userId <= 0 || originalOrderId <= 0 || (amend == null) == (replace == null)
                || decision == null || openInterestSteps < 0 || runtime == null || identities == null
                || matcherShard < 0 || matcherShard >= runtime.topology().matchingEngineCount())
            throw new IllegalArgumentException("invalid replacement admission");
        this.sequence = sequence; this.userId = userId; this.originalOrderId = originalOrderId;
        this.amend = amend; this.replace = replace; this.decision = decision;
        this.matcherShard = matcherShard; this.openInterestSteps = openInterestSteps; this.runtime = runtime; this.identities = identities;
        admission = null; rejection = null; completed = false; closeCapacityCancellations = List.of();
        return this;
    }

    @Override public void execute(AccountLaneState lane) {
        TradingRuntimeState completionRuntime = runtime;
        int completionShard = matcherShard;
        int completionLane = lane.laneId();
        if (completionLane != runtime.topology().accountLaneId(userId))
            throw new IllegalStateException("replacement admission crossed Account Lane");
        runtime.enterLaneCommandScope(lane);
        try {
            OrderRuntime original = lane.orders.get(originalOrderId);
            if (original == null) throw rejected("ORDER_NOT_FOUND", "order does not exist");
            if (original.userId() != userId) throw rejected("ORDER_OWNER_MISMATCH", "order belongs to another user");
            if (original.status() != CoreOrderStatus.OPEN) throw rejected("INVALID_COMMAND", "order is not replaceable");
            if (amend != null && original.orderType() != CoreOrderType.LIMIT)
                throw rejected("INVALID_COMMAND", "order is not amendable");
            PlaceOrderCommand command = replace == null ? amendCommand(original) : replace.replacement();
            CoreOrderDecisionResolver.resolveInto(this, decision, command);
            long positionKey = identities.findPositionKeyValueInLane(lane, userId, instrument(), positionSide());
            long required = RuntimeOrderAdmission.requiredReplacementReservationPrepared(
                    runtime, userId, this, openInterestSteps, lane.admissionOrderIndex(symbolId()),
                    originalOrderId, identities.findClientKeyValueInLane(lane, userId, clientOrderId()), positionKey,
                    decision.lifecycleSettled(), decision.fundingInProgress());
            PositionRuntime position = positionKey == 0 ? null : lane.positions.get(positionKey);
            if (instrument().contractType().productLine().isDerivative() && !reduceOnly()
                    && position != null && position.signedQuantitySteps() != 0
                    && (position.signedQuantitySteps() > 0)
                    != (side() == com.surprising.aeron.protocol.CoreOrderSide.BUY)) {
                closeCapacityCancellations = PositionCloseCapacity.inspectLane(
                        lane, userId, symbolId(), positionKey, positionSide(), side(), originalOrderId)
                        .conflictsFor(quantitySteps());
            }
            var user = lane.users.get(userId);
            admission = new ResolvedMatchingAdmission(userId, originalOrderId, original.revision(),
                    user == null ? 0 : user.revision(), command, this, required);
        } catch (CoreStateRejectedException | ArithmeticException | IllegalArgumentException failure) {
            rejection = failure;
        } finally { runtime.exitLaneCommandScope(lane); }
        // The Owner can retire/rearm this pooled event as soon as completion is visible.
        // Capture every notification input first; never read event fields after release.
        completed = true;
        completionRuntime.publishAccountAdmissionReadyShard(completionShard);
    }

    private PlaceOrderCommand amendCommand(OrderRuntime original) {
        return new PlaceOrderCommand(amend.replacementOrderId(), decision.instrument().instrumentId(),
                original.side(), amend.priceTicks() == null ? original.priceTicks() : amend.priceTicks(),
                amend.quantitySteps() == null ? original.remainingQuantitySteps() : amend.quantitySteps(),
                original.reduceOnly(), original.marginMode(), original.positionSide(), original.orderType(),
                amend.timeInForce() == null ? original.timeInForce() : amend.timeInForce(),
                amend.postOnly() == null ? original.postOnly() : amend.postOnly(),
                amend.newClientOrderId() == null ? "" : amend.newClientOrderId());
    }

    public boolean complete() { return completed; }
    public long coreSequence() { return sequence; }
    public RuntimeException rejection() { requireComplete(); return rejection; }
    public ResolvedMatchingAdmission admission() { requireComplete(); return admission; }
    public List<Long> closeCapacityCancellations() { requireComplete(); return closeCapacityCancellations; }

    @Override public void clear() {
        requireComplete();
        clearResolvedOrder();
        amend = null; replace = null; decision = null; runtime = null; identities = null;
        admission = null; rejection = null; closeCapacityCancellations = List.of(); completed = false;
    }

    private void requireComplete() {
        if (!completed) throw new IllegalStateException("replacement admission is incomplete");
    }
    private static CoreStateRejectedException rejected(String code, String message) {
        return new CoreStateRejectedException(code, message);
    }
}
