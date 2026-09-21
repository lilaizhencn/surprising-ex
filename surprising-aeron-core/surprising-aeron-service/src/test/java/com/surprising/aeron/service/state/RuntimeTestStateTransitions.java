package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.state.admission.CoreOrderDecisionResolver;
import com.surprising.aeron.service.state.index.ActiveOrderIndex;
import com.surprising.aeron.service.state.model.CoreOrderState;
import com.surprising.aeron.service.state.query.RuntimeRiskQueryService;
import exchange.core2.core.common.MatcherResult.MatcherEvent;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Test fixture that drives the production mutable runtime and materializes only at assertion boundaries. */
final class RuntimeTestStateTransitions {

    RuntimeTestStateTransitions() {
    }

    RuntimeTestStateTransitions(LaneTopology ignored) {
    }

    TradingCoreState registerInstrument(TradingCoreState before, RegisterInstrumentCommand command) {
        var instrument = com.surprising.aeron.service.state.instrument.CoreInstrument.from(
                before.productLine(), command);
        if (before.instruments().containsKey(instrument.symbol())) {
            throw new com.surprising.aeron.service.exception.CoreStateRejectedException(
                    "INVALID_COMMAND", "instrument is already registered");
        }
        var instruments = new java.util.HashMap<>(before.instruments());
        instruments.put(instrument.symbol(), instrument);
        return new TradingCoreState(before.productLine(), Math.incrementExact(before.revision()),
                before.users(), before.orders(), instruments, before.riskState(), before.treasuryState(),
                before.leverages(), before.algoOrders(), before.cancelAllAfterTimers(),
                before.clientOrderIndex(), before.triggerOrders());
    }

    TradingCoreState adjustBalance(TradingCoreState before, long userId, BalanceAdjustmentCommand command) {
        return mutate(before, (runtime, identities) ->
                RuntimeAccountStateTransitions.adjustBalance(runtime, identities, userId, command));
    }

    TradingCoreState updateRiskScanControl(TradingCoreState before, UpdateRiskScanControlCommand command,
                                           long updatedAtEpochMillis) {
        return mutate(before, (runtime, identities) ->
                RuntimeRiskStateTransitions.updateScanControl(runtime, command, updatedAtEpochMillis));
    }

    TradingCoreState updatePositionMode(TradingCoreState before, long userId, UpdatePositionModeCommand command) {
        return mutate(before, (runtime, identities) ->
                DerivativeAccountCommandProcessor.updatePositionMode(runtime, userId, command));
    }

    TradingCoreState updateLeverage(TradingCoreState before, long userId, UpdateLeverageCommand command) {
        return mutate(before, (runtime, identities) ->
                DerivativeAccountCommandProcessor.updateLeverage(runtime, identities, userId, command));
    }

    TradingCoreState adjustPositionMargin(TradingCoreState before, long userId,
                                          AdjustPositionMarginCommand command) {
        return mutate(before, (runtime, identities) ->
                DerivativeAccountCommandProcessor.adjustPositionMargin(runtime, identities, userId, command));
    }

    TradingCoreState applyMarkPrice(TradingCoreState before, ApplyMarkPriceCommand command) {
        return mutate(before, (runtime, identities) -> RuntimeDerivativeRiskProcessor.applyMarkPrice(
                before, command, before.users().keySet(), runtime, identities));
    }

    TradingCoreState continueRiskScan(TradingCoreState before, int maxUsers) {
        return mutate(before, (runtime, identities) -> RuntimeDerivativeRiskProcessor.applyContinuation(
                before, maxUsers, before.users().keySet(), runtime, identities));
    }

    TradingCoreState placeOrder(TradingCoreState before, long userId, PlaceOrderCommand command) {
        return placeOrder(before, userId, command, UUID.randomUUID(), 0, new ActiveOrderIndex(before));
    }

    TradingCoreState placeOrder(TradingCoreState before, long userId, ResolvedPlaceOrder command) {
        return mutate(before, (runtime, identities) -> {
            ActiveOrderIndex activeOrders = new ActiveOrderIndex(before);
            long required = RuntimeOrderAdmission.requiredReservation(runtime, identities, userId,
                    command, 0, activeOrders);
            RuntimeOrderStateTransitions.place(runtime, identities, userId, command, UUID.randomUUID(), required);
        });
    }

    TradingCoreState placeOrder(TradingCoreState before, long userId, PlaceOrderCommand command, UUID commandId) {
        return placeOrder(before, userId, command, commandId, 0, new ActiveOrderIndex(before));
    }

    TradingCoreState placeOrder(TradingCoreState before, long userId, PlaceOrderCommand command,
                                UUID commandId, long openInterestSteps, ActiveOrderIndex activeOrders) {
        return mutate(before, (runtime, identities) -> {
            ResolvedPlaceOrder resolved = CoreOrderDecisionResolver.resolve(before, command);
            long required = RuntimeOrderAdmission.requiredReservation(runtime, identities, userId,
                    resolved, openInterestSteps, activeOrders);
            RuntimeOrderStateTransitions.place(runtime, identities, userId, resolved, commandId, required);
        });
    }

    TradingCoreState cancelOrder(TradingCoreState before, long userId, CancelOrderCommand command) {
        return mutate(before, (runtime, identities) ->
                RuntimeOrderStateTransitions.cancel(runtime, userId, command.orderId()));
    }

    TradingCoreState upsertAlgoOrder(TradingCoreState before, long userId, CoreAlgoOrderView view) {
        return mutate(before, (runtime, identities) ->
                RuntimeAlgoOrderStateTransitions.upsert(runtime, identities, userId, view));
    }

    TradingCoreState updateCancelAllAfter(TradingCoreState before, long userId,
                                          CoreCancelAllAfterCommand command) {
        return mutate(before, (runtime, identities) ->
                RuntimeCancelAllAfterStateTransitions.update(runtime, userId, command));
    }

    TradingCoreState applyMatches(TradingCoreState before, long takerOrderId,
                                  String baseAsset, String quoteAsset, List<MatcherEvent> matches) {
        return mutate(before, (runtime, identities) -> {
            if (before.productLine() == com.surprising.product.api.ProductLine.SPOT) {
                RuntimeSpotMatchProcessor.apply(before, takerOrderId, baseAsset, quoteAsset,
                        matches, runtime, identities);
            } else {
                RuntimeDerivativeMatchProcessor.apply(before, takerOrderId, matches, runtime, identities);
            }
        });
    }

    FundingApplication applyFundingWithFacts(TradingCoreState before, ApplyFundingCommand command) {
        return applyFundingWithFacts(before, command, null, null);
    }

    FundingApplication applyFundingWithFacts(TradingCoreState before, ApplyFundingCommand command,
                                              Iterable<Long> userIds) {
        return applyFundingWithFacts(before, command, userIds, null);
    }

    FundingApplication applyFundingWithFacts(TradingCoreState before, ApplyFundingCommand command,
                                              Iterable<Long> userIds, UUID chunkCommandId) {
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        try (TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities)) {
            var result = RuntimePerpetualFundingProcessor.apply(before, command,
                    userIds, chunkCommandId, runtime, identities);
            return new FundingApplication(RuntimeStateMaterializer.materialize(runtime, identities),
                    result.payments(), result.progress());
        }
    }

    TradingCoreState applyFunding(TradingCoreState before, ApplyFundingCommand command) {
        return applyFundingWithFacts(before, command).state();
    }

    SettlementApplication settleInstrumentWithProgress(TradingCoreState before,
                                                        SettleInstrumentCommand command,
                                                        Iterable<Long> userIds, UUID chunkCommandId) {
        return settleInstrumentWithProgress(before, command, userIds, chunkCommandId, null);
    }

    SettlementApplication settleInstrumentWithProgress(TradingCoreState before,
                                                        SettleInstrumentCommand command,
                                                        Iterable<Long> userIds, UUID chunkCommandId,
                                                        ActiveOrderIndex activeOrders) {
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        try (TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities)) {
            var progress = RuntimeLifecycleSettlement.apply(before, command, userIds, chunkCommandId,
                    activeOrders, runtime, identities);
            return new SettlementApplication(RuntimeStateMaterializer.materialize(runtime, identities), progress);
        }
    }

    TradingCoreState settleInstrument(TradingCoreState before, SettleInstrumentCommand command) {
        return settleInstrumentWithProgress(before, command, null, null, null).state();
    }

    TradingCoreState settleInstrument(TradingCoreState before, SettleInstrumentCommand command,
                                      Iterable<Long> userIds) {
        return settleInstrumentWithProgress(before, command, userIds, null, null).state();
    }

    TradingCoreState executeLiquidation(TradingCoreState before, ExecuteLiquidationCommand command) {
        return mutate(before, (runtime, identities) -> RuntimeDerivativeLiquidationProcessor.applyExecution(
                before, command, openOrders(before), runtime, identities));
    }

    TradingCoreState executeLiquidationAfterCancellation(TradingCoreState before,
                                                          ExecuteLiquidationCommand command) {
        return mutate(before, (runtime, identities) -> RuntimeDerivativeLiquidationProcessor.applyExecution(
                before, command, List.of(), runtime, identities));
    }

    TradingCoreState advanceLiquidationCancellation(TradingCoreState before,
                                                     ExecuteLiquidationCommand command,
                                                     Collection<CoreOrderState> canceledOrders,
                                                     long nextCursorOrderId) {
        return mutate(before, (runtime, identities) ->
                RuntimeDerivativeLiquidationProcessor.applyCancellationAdvance(before, command,
                        canceledOrders, nextCursorOrderId, runtime, identities));
    }

    TradingCoreState cancelLifecycleOrders(TradingCoreState before,
                                           Collection<CoreOrderState> canceledOrders) {
        return mutate(before, (runtime, identities) -> {
            for (CoreOrderState order : canceledOrders) {
                OrderRuntime current = runtime.order(order.orderId());
                ReservationRuntime reservation = runtime.reservation(order.orderId());
                runtime.cancelOrder(order.orderId(), order.userId(), reservation.reservedUnits());
            }
            runtime.incrementCommandRevision();
        });
    }

    TradingCoreState resolveLiquidation(TradingCoreState before, ResolveLiquidationCommand command) {
        return mutate(before, (runtime, identities) ->
                RuntimeLiquidationResolution.apply(before, command, runtime, identities));
    }

    TradingCoreState adjustInsuranceFund(TradingCoreState before, AdjustInsuranceFundCommand command) {
        return mutate(before, (runtime, identities) ->
                RuntimeInsuranceFundStateTransitions.adjust(runtime, identities, command));
    }

    TradingCoreState executeAdl(TradingCoreState before, ExecuteAdlCommand command) {
        return mutate(before, (runtime, identities) -> RuntimeAdlExecution.apply(before, command, runtime, identities));
    }

    List<CoreAdlCandidateView> adlCandidates(TradingCoreState before, String asset, int limit) {
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        try (TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities)) {
            return RuntimeRiskQueryService.adlCandidates(runtime, identities, asset,
                    new AdlPositionIndex(before, identities).positions(asset), limit);
        }
    }

    List<CoreRiskSnapshotView> riskSnapshots(TradingCoreState before, long userId) {
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        try (TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities)) {
            return RuntimeRiskQueryService.snapshots(runtime, identities, userId);
        }
    }

    private static List<CoreOrderState> openOrders(TradingCoreState state) {
        return state.orders().values().stream().filter(order -> !order.status().terminal()).toList();
    }

    private static TradingCoreState mutate(TradingCoreState before, Mutation mutation) {
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        try (TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities)) {
            mutation.apply(runtime, identities);
            return RuntimeStateMaterializer.materialize(runtime, identities);
        }
    }

    @FunctionalInterface
    private interface Mutation {
        void apply(TradingRuntimeState runtime, RuntimeIdentityRegistry identities);
    }

    record FundingApplication(TradingCoreState state, List<CoreFundingPaymentView> payments,
                              CoreFundingProgressView progress) {
    }

    record SettlementApplication(TradingCoreState state, CoreSettlementProgressView progress) {
    }
}
