package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.service.state.admission.CoreOrderDecisionResolver;

import java.util.List;
import java.util.UUID;
import java.util.function.IntFunction;

/** 一个顺序批量项的 Lane 准入和冻结；批次槽复用，完成回执不复制账户状态。 */
public final class BatchPlaceReservation extends ResolvedPlaceOrder implements IntFunction<Object>, SystemLedgerLane.Entry, java.util.function.BooleanSupplier {
    private TradingRuntimeState runtime;
    private RuntimeIdentityRegistry identities;
    private PlaceBatchIntentSource.Decision decision;
    private long sequence, userId, openInterestSteps;
    private UUID commandId;
    private int assetId;
    private long preparedClientKey;
    private OrderRuntime admittedOrder;
    /** Lane writes the existing batch's item lease, which outlives this per-item task and Lane output. */
    private PlaceBatchIntentSource source;
    private int sourceIndex;
    private List<Long> closeCapacityCancellations = List.of();
    private volatile boolean completed = true;
    private volatile boolean inputReady = true;
    private boolean inputQueued, accountDispatched;

    public void prepare(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
            long sequence, long userId, UUID commandId, PlaceOrderCommand command,
            PlaceBatchIntentSource.Decision decision, PlaceBatchIntentSource source, int sourceIndex) {
        if (runtime == null || identities == null || sequence <= 0 || userId <= 0
                || commandId == null || command == null || decision == null || source == null || sourceIndex < 0)
            throw new IllegalArgumentException("invalid batch place reservation");
        runtime.assertOwner();
        if (!completed) throw new IllegalStateException("Account Lane still owns this batch reservation");
        clear();
        this.decision = decision;
        this.source = source;
        this.sourceIndex = sourceIndex;
        initializeOrderIntent(command, decision.context().instrument(), decision.context().symbolId());
        this.runtime = runtime;
        this.identities = identities;
        this.sequence = sequence;
        this.userId = userId;
        this.commandId = commandId;
        openInterestSteps = 0;
        inputReady = !decision.context().instrument().usesOpenInterestForUserLimit() || command.reduceOnly();
        inputQueued = accountDispatched = false;
        completed = false;
    }

    @Override public boolean getAsBoolean() { return pollAdmission(); }

    /** Owner advances mail only; Ledger supplies OI before the Account Lane sees this item. */
    public boolean pollAdmission() {
        runtime.assertOwner();
        if (!inputReady) {
            if (!inputQueued) inputQueued = runtime.dispatchLedgerQuery(this);
            if (!inputReady) return false;
        }
        if (!accountDispatched) {
            runtime.dispatchControlLanes(runtime.topology().accountLaneMask(userId), this);
            accountDispatched = true;
        }
        return runtime.pollControlLanes();
    }

    @Override public void apply(TreasuryRuntime ignored) {
        openInterestSteps = runtime.ledgerAdmissionOpenInterest(decision.context().instrument().instrumentId());
        TradingRuntimeState completion = runtime;
        inputReady = true;
        completion.signalOwnerCompletion();
    }

    @Override public Object apply(int laneId) {
        if (!inputReady) throw new IllegalStateException("Ledger batch admission input is incomplete");
        AccountLaneState lane = runtime.currentLaneCommandScope();
        if (lane == null || lane.laneId() != laneId || laneId != runtime.topology().accountLaneId(userId))
            throw new IllegalStateException("batch place reservation requires its Account Lane");
        long identity = 0;
        try {
            CoreOrderDecisionResolver.resolveInto(this, decision.context(), userId, intent());
            assetId = runtime.productLine().isDerivative() ? decision.settleAssetId()
                    : side() == CoreOrderSide.BUY ? decision.quoteAssetId() : decision.baseAssetId();
            identity = identities.prepareClientKeyInLane(lane, userId, clientOrderId());
            preparedClientKey = identity;
            long clientKey = RuntimeIdentityRegistry.clientKeyValue(identity);
            long positionKey = identities.findPositionKeyValueInLane(lane, userId, instrument(), positionSide());
            long required = RuntimeOrderAdmission.requiredReservationPrepared(runtime, lane, userId, this,
                    openInterestSteps, lane.admissionOrderIndex(symbolId()), clientKey, symbolId(), positionKey,
                    decision.context().lifecycleSettled(), decision.context().fundingInProgress());
            PositionRuntime position = positionKey == 0 ? null : lane.positions.get(positionKey);
            if (instrument().contractType().productLine().isDerivative() && !reduceOnly()
                    && position != null && position.signedQuantitySteps() != 0
                    && (position.signedQuantitySteps() > 0) != (side() == CoreOrderSide.BUY)) {
                closeCapacityCancellations = PositionCloseCapacity.inspectLane(lane, userId, symbolId(),
                        positionKey, positionSide(), side(), orderId()).conflictsFor(quantitySteps());
            }
            RuntimeOrderStateTransitions.reserveBatchOrderInLane(runtime, userId, this, commandId,
                    required, clientKey, assetId, sequence);
            OrderRuntime admitted = runtime.laneDelta(laneId).orders.get(orderId());
            if (admitted == null) throw new IllegalStateException("batch admission order is missing");
            admittedOrder = source.captureAdmittedOrder(sourceIndex, admitted);
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
        decision = null;
        commandId = null;
        sequence = userId = openInterestSteps = preparedClientKey = 0;
        assetId = 0;
        admittedOrder = null;
        source = null;
        sourceIndex = 0;
        closeCapacityCancellations = List.of();
        inputReady = true;
        inputQueued = accountDispatched = false;
    }
}
