package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.service.matching.DeterministicExchangeCoreAdapter;
import com.surprising.aeron.service.state.OrderRuntime;
import com.surprising.aeron.service.command.order.ResolvedMatchingAdmission;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Converts an admitted command slot into the deterministic command consumed by the Matcher.
 * Product settlement and account state stay in the runtime; this class owns only Matcher input
 * selection, pre-cancellation evidence and the control/shard evidence envelope.
 */
final class MatcherCommandSubmission {
    private final TradingCoreRuntime owner;

    MatcherCommandSubmission(TradingCoreRuntime owner) {
        this.owner = java.util.Objects.requireNonNull(owner);
    }

    Supplier<com.surprising.aeron.service.matching.CoreMatchingResult> prepareMatchingCommand(CommandSlot pending) {
        if (TradingCoreRuntime.MATCHING_PHASE_METRICS_ENABLED) {
            owner.matchingSubmitNanos.put(pending.sequence(), System.nanoTime());
        }
        try {
            List<DeterministicExchangeCoreAdapter.CancellationOrder> preMatchingCancellations =
                    preMatchingCancellationOrders(pending);
            long userId = pending.command().header().userId();
            if (pending.operation() == CommandSlot.Operation.PLACE && preMatchingCancellations.isEmpty()) {
                var command = pending.decodedCommand().placeOrder();
                var admittedOrder = pending.admittedPlaceOrder();
                int shard = owner.matcherShard(pending);
                if (admittedOrder != null) {
                    return pending.preparePlaceMatching(owner, shard, command.instrumentChangeId(), userId,
                            admittedOrder, null);
                }
                // Ordinary PLACE admissions run on the Account Lane and Matcher concurrently.
                // Until the Lane publishes its mutable runtime object, use the immutable input
                // captured by the admission event.
                var order = pending.placeAdmission() == null
                        ? owner.matchingOrder(command.orderId()) : pending.placeAdmission().matchingOrder();
                return pending.preparePlaceMatching(owner, shard, command.instrumentChangeId(), userId,
                        null, order);
            }
            if (pending.operation() == CommandSlot.Operation.CANCEL && preMatchingCancellations.isEmpty()) {
                var command = pending.decodedCommand().cancelOrder();
                var order = owner.runtimeState.order(command.orderId());
                if (order != null) {
                    String symbol = owner.identities.symbol(order.symbolId());
                    long instrumentChangeId = order.instrumentChangeId();
                    int shard = owner.matcherShard(pending);
                    return pending.prepareCancelMatching(owner, shard, command.orderId(), instrumentChangeId,
                            userId, symbol);
                }
            }
            if ((pending.operation() == CommandSlot.Operation.REPLACE
                    || pending.operation() == CommandSlot.Operation.AMEND)
                    && preMatchingCancellations.isEmpty()) {
                ResolvedMatchingAdmission admission = owner.requireMatchingAdmission(pending);
                owner.requireUnchangedAdmissionState(admission);
                var order = owner.runtimeOrder(admission.originalOrderId());
                String symbol = owner.runtimeOrderSymbol(order);
                return pending.prepareReplaceMatching(owner, owner.matcherShard(pending),
                        admission.resolved().orderId(), admission.resolved().instrumentChangeId(), userId,
                        admission.originalOrderId(), symbol, admission.matchingOrder());
            }
            MatchingSubmission matching = switch (pending.operation()) {
                case PLACE -> {
                    var command = pending.decodedCommand().placeOrder();
                    var admittedOrder = pending.admittedPlaceOrder();
                    if (admittedOrder != null) {
                        yield new MatchingSubmission(command.orderId(), command.instrumentChangeId(),
                                () -> owner.matchingAdapter.place(userId, admittedOrder));
                    }
                    var order = pending.placeAdmission() == null
                            ? owner.matchingOrder(command.orderId()) : pending.placeAdmission().matchingOrder();
                    yield new MatchingSubmission(command.orderId(), command.instrumentChangeId(),
                            () -> owner.matchingAdapter.place(userId, order));
                }
                case CANCEL -> {
                    var command = pending.decodedCommand().cancelOrder();
                    var order = owner.runtimeState.order(command.orderId());
                    String symbol = order == null ? "" : owner.identities.symbol(order.symbolId());
                    long instrumentChangeId = order == null ? 0 : order.instrumentChangeId();
                    yield new MatchingSubmission(command.orderId(), instrumentChangeId,
                            () -> owner.matchingAdapter.cancelForContinuation(userId, command.orderId(), symbol));
                }
                case REPLACE, AMEND -> {
                    ResolvedMatchingAdmission admission = owner.requireMatchingAdmission(pending);
                    var order = owner.runtimeOrder(admission.originalOrderId());
                    String symbol = owner.runtimeOrderSymbol(order);
                    yield new MatchingSubmission(admission.resolved().orderId(),
                            admission.resolved().instrumentChangeId(),
                            () -> owner.matchingAdapter.replaceOrder(userId, admission.originalOrderId(),
                                    symbol, admission.matchingOrder()));
                }
                case TRIGGER -> {
                    long[] execute = pending.decodedCommand().trigger();
                    var trigger = owner.runtimeState.triggerOrder(execute[0]);
                    if (trigger == null) {
                        yield new MatchingSubmission(0, 0, () ->
                                new com.surprising.aeron.service.matching.CoreMatchingResult(
                                        false, "TRIGGER_ORDER_NOT_FOUND"));
                    }
                    var order = java.util.Objects.requireNonNull(pending.admittedMatchingOrder(),
                            "trigger admission is missing");
                    yield new MatchingSubmission(order.orderId(), trigger.instrumentChangeId(),
                            () -> owner.matchingAdapter.place(trigger.userId(), order));
                }
                case LIQUIDATION -> {
                    var command = pending.decodedCommand().liquidation();
                    var liquidation = owner.runtimeState.liquidation(command.liquidationId());
                    if (liquidation == null || !com.surprising.aeron.service.state.query.RuntimeLiquidationQueryService
                            .isExecutable(owner.runtimeState, owner.identities, command)) {
                        yield new MatchingSubmission(command.liquidationId(),
                                liquidation == null ? 0 : liquidation.instrumentChangeId(), () ->
                                new com.surprising.aeron.service.matching.CoreMatchingResult(true, "SUCCESS"));
                    }
                    var orders = owner.lifecycleOrders(liquidation.userId(),
                            owner.runtimeLiquidationSymbol(liquidation), command.cursorOrderId(),
                            command.maxOrders()).orders();
                    yield new MatchingSubmission(command.liquidationId(), liquidation.instrumentChangeId(),
                            () -> owner.matchingAdapter.cancelBatch(orders));
                }
                case LIQUIDATION_BATCH -> {
                    var orders = owner.batchCancellationOrders(pending);
                    yield new MatchingSubmission(0, 0, () -> owner.matchingAdapter.cancelBatch(orders));
                }
                case SETTLEMENT -> {
                    var command = pending.decodedCommand().settlement();
                    var progress = owner.runtimeLifecycleProgress(command.symbol());
                    if (progress != null && progress.ordersComplete()) {
                        yield new MatchingSubmission(0, command.instrumentChangeId(), () ->
                                new com.surprising.aeron.service.matching.CoreMatchingResult(true, "SUCCESS"));
                    }
                    var orders = owner.lifecycleOrders(0, command.symbol(), command.cursorOrderId(),
                            command.maxOrders()).orders();
                    yield new MatchingSubmission(0, command.instrumentChangeId(),
                            () -> owner.matchingAdapter.cancelBatch(orders));
                }
            };
            Supplier<com.surprising.aeron.service.matching.CoreMatchingResult> guarded =
                    () -> owner.matchingAdapter.executeAfterCancellationsSync(
                            preMatchingCancellations, matching.submission());
            return withEvidence(pending, matching.orderId(), matching.instrumentChangeId(),
                    isControlCommand(pending), guarded);
        } catch (RuntimeException exception) {
            return withEvidence(pending, 0, 0, isControlCommand(pending), () ->
                    new com.surprising.aeron.service.matching.CoreMatchingResult(false, "EXCHANGE_CORE_FAILURE"));
        }
    }

    private boolean isControlCommand(CommandSlot pending) {
        return pending.operation() == CommandSlot.Operation.LIQUIDATION
                || pending.operation() == CommandSlot.Operation.LIQUIDATION_BATCH
                || pending.operation() == CommandSlot.Operation.SETTLEMENT;
    }

    private Supplier<com.surprising.aeron.service.matching.CoreMatchingResult> withEvidence(
            CommandSlot pending, long orderId, long instrumentChangeId, boolean control,
            Supplier<com.surprising.aeron.service.matching.CoreMatchingResult> command) {
        long coreSequence = pending.sequence();
        UUID commandId = pending.command().header().commandId();
        long aeronTimestamp = pending.command().header().submittedAtEpochMillis();
        int shard = control ? -1 : owner.matcherShard(pending);
        return control
                ? () -> owner.matchingAdapter.executeControlWithEvidenceSync(
                        coreSequence, commandId, orderId, instrumentChangeId, aeronTimestamp, command)
                : () -> owner.matchingAdapter.executeShardWithEvidenceSync(
                        shard, coreSequence, commandId, orderId, instrumentChangeId, aeronTimestamp, command);
    }

    List<DeterministicExchangeCoreAdapter.CancellationOrder> preMatchingCancellationOrders(
            CommandSlot pending) {
        List<Long> orderIds = pending.preMatchingCancellationOrderIds();
        if (orderIds.isEmpty()) return List.of();
        ArrayList<DeterministicExchangeCoreAdapter.CancellationOrder> orders =
                new ArrayList<>(orderIds.size());
        for (long orderId : orderIds) {
            OrderRuntime order = owner.runtimeOrder(orderId);
            if (order != null && order.status() == com.surprising.aeron.service.state.model.CoreOrderStatus.OPEN) {
                orders.add(new DeterministicExchangeCoreAdapter.CancellationOrder(
                        order.orderId(), order.userId(), owner.runtimeOrderSymbol(order)));
            }
        }
        return orders.isEmpty() ? List.of() : orders;
    }

    private record MatchingSubmission(
            long orderId,
            long instrumentChangeId,
            Supplier<com.surprising.aeron.service.matching.CoreMatchingResult> submission) {
    }
}
