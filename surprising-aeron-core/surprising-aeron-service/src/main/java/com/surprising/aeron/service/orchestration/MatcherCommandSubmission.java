package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.service.matching.DeterministicExchangeCoreAdapter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Converts an admitted command slot into the deterministic command consumed by the Matcher.
 * Product settlement and account state stay in the runtime; this class owns only Matcher input
 * selection and stable pre-cancellation routes. Control commands cancel through an explicit API.
 */
final class MatcherCommandSubmission {
    private final TradingCoreRuntime owner;

    MatcherCommandSubmission(TradingCoreRuntime owner) {
        this.owner = java.util.Objects.requireNonNull(owner);
    }

    Supplier<?> prepareMatchingCommand(CommandSlot pending,
            com.surprising.aeron.service.state.MatcherSettlementEvent direct) {
        if (isControlCommand(pending)) return prepareControlCancellations(pending);
        java.util.Objects.requireNonNull(direct, "trading command requires its direct settlement slot");
        pending.matcherPreCancellations(pending.operation() == CommandSlot.Operation.PLACE
                && pending.placeAdmission() != null ? List.of() : preMatchingCancellationOrders(pending));
        long userId = pending.command().header().userId();
        int shard = owner.matchingFlow.matcherShard(pending);
        return switch (pending.operation()) {
            case PLACE -> pending.preparePlaceMatching(owner, shard, userId,
                    java.util.Objects.requireNonNull(pending.placeAdmission(), "place admission receipt").matchingOrder(),
                    null, direct);
            case CANCEL -> {
                var command = pending.decodedCommand().cancelOrder();
                var route = java.util.Objects.requireNonNull(owner.activeOrderIndex.activeOrderRoute(command.orderId()),
                        "admitted cancellation route");
                yield pending.prepareCancelMatching(owner, shard, command.orderId(), userId, route.instrumentId(), direct);
            }
            case REPLACE, AMEND -> {
                var admission = owner.requireMatchingAdmission(pending);
                yield pending.prepareReplaceMatching(owner, shard, admission.resolved().orderId(), userId,
                        admission.originalOrderId(), admission.command().instrumentId(), admission.resolved(), direct);
            }
            case TRIGGER -> pending.preparePlaceMatching(owner, shard, userId, null,
                    java.util.Objects.requireNonNull(pending.admittedMatchingOrder(), "trigger admission receipt"), direct);
            default -> throw new IllegalStateException("unsupported trading operation " + pending.operation());
        };
    }

    private boolean isControlCommand(CommandSlot pending) {
        return pending.operation() == CommandSlot.Operation.LIQUIDATION
                || pending.operation() == CommandSlot.Operation.LIQUIDATION_BATCH
                || pending.operation() == CommandSlot.Operation.SETTLEMENT;
    }

    private Supplier<?> prepareControlCancellations(CommandSlot pending) {
        long logicalOrderId = 0;
        List<DeterministicExchangeCoreAdapter.CancellationOrder> orders;
        switch (pending.operation()) {
            case LIQUIDATION -> {
                var command = pending.decodedCommand().liquidation();
                logicalOrderId = command.liquidationId();
                var liquidation = pending.liquidationCheck().liquidation(0);
                orders = liquidation == null || !pending.liquidationCheck().executable(0) ? List.of()
                        : owner.matchingFlow.lifecycleOrders(liquidation.userId(), owner.runtimeLiquidationSymbol(liquidation),
                                command.cursorOrderId(), command.maxOrders()).orders();
            }
            case LIQUIDATION_BATCH -> orders = owner.matchingFlow.batchCancellationOrders(pending);
            case SETTLEMENT -> {
                var command = pending.decodedCommand().settlement();
                var progress = owner.runtimeLifecycleProgress(command.instrumentId());
                orders = progress != null && progress.ordersComplete() ? List.of()
                        : owner.matchingFlow.lifecycleOrders(0, command.instrumentId(),
                                command.cursorOrderId(), command.maxOrders()).orders();
            }
            default -> throw new IllegalStateException("unsupported cancellation control");
        }
        var prefix = preMatchingCancellationOrders(pending);
        if (!prefix.isEmpty()) {
            var combined = new ArrayList<DeterministicExchangeCoreAdapter.CancellationOrder>(prefix.size() + orders.size());
            combined.addAll(prefix); combined.addAll(orders); orders = combined;
        }
        return pending.prepareControlMatching(owner, logicalOrderId, orders);
    }

    List<DeterministicExchangeCoreAdapter.CancellationOrder> preMatchingCancellationOrders(
            CommandSlot pending) {
        List<Long> orderIds = pending.preMatchingCancellationOrderIds();
        if (orderIds.isEmpty()) return List.of();
        ArrayList<DeterministicExchangeCoreAdapter.CancellationOrder> orders =
                new ArrayList<>(orderIds.size());
        for (long orderId : orderIds) {
            var order = owner.activeOrderIndex.activeOrderRoute(orderId);
            if (order != null) {
                orders.add(new DeterministicExchangeCoreAdapter.CancellationOrder(
                        order.orderId(), order.userId(), order.instrumentId()));
            }
        }
        return orders.isEmpty() ? List.of() : orders;
    }

}
