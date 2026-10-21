package com.surprising.aeron.service.state.order;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.protocol.AdjustInsuranceFundCommand;
import com.surprising.aeron.protocol.AdjustPositionMarginCommand;
import com.surprising.aeron.protocol.ApplyFundingCommand;
import com.surprising.aeron.protocol.ApplyMarkPriceCommand;
import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CancelOrderCommand;
import com.surprising.aeron.protocol.CoreAdlCandidateView;
import com.surprising.aeron.protocol.CoreAlgoOrderView;
import com.surprising.aeron.protocol.CoreCancelAllAfterCommand;
import com.surprising.aeron.protocol.CoreFundingPaymentView;
import com.surprising.aeron.protocol.CoreFundingProgressView;
import com.surprising.aeron.protocol.CoreRiskSnapshotView;
import com.surprising.aeron.protocol.CoreSettlementProgressView;
import com.surprising.aeron.protocol.ExecuteAdlCommand;
import com.surprising.aeron.protocol.ExecuteLiquidationCommand;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.RegisterInstrumentCommand;
import com.surprising.aeron.protocol.ResolveLiquidationCommand;
import com.surprising.aeron.protocol.SettleInstrumentCommand;
import com.surprising.aeron.protocol.UpdateLeverageCommand;
import com.surprising.aeron.protocol.UpdatePositionModeCommand;
import com.surprising.aeron.protocol.UpdateRiskScanControlCommand;
import com.surprising.aeron.service.lane.LaneTopology;
import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.aeron.service.state.TradingRuntimeState;
import com.surprising.aeron.service.state.account.DerivativeAccountCommandProcessor;
import com.surprising.aeron.service.state.account.RuntimeAccountStateTransitions;
import com.surprising.aeron.service.state.admission.CoreOrderDecisionResolver;
import com.surprising.aeron.service.state.admission.ResolvedPlaceOrder;
import com.surprising.aeron.service.state.admission.RuntimeOrderAdmission;
import com.surprising.aeron.service.state.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.state.index.ActiveOrderIndex;
import com.surprising.aeron.service.state.index.AdlPositionIndex;
import com.surprising.aeron.service.state.ledger.RuntimeInsuranceFundStateTransitions;
import com.surprising.aeron.service.state.lifecycle.RuntimeLifecycleSettlement;
import com.surprising.aeron.service.state.lifecycle.RuntimePerpetualFundingProcessor;
import com.surprising.aeron.service.state.model.CoreOrderState;
import com.surprising.aeron.service.state.query.RuntimeRiskQueryService;
import com.surprising.aeron.service.state.risk.RuntimeAdlExecution;
import com.surprising.aeron.service.state.risk.RuntimeDerivativeLiquidationProcessor;
import com.surprising.aeron.service.state.risk.RuntimeDerivativeRiskProcessor;
import com.surprising.aeron.service.state.risk.RuntimeLiquidationResolution;
import com.surprising.aeron.service.state.risk.RuntimeRiskStateTransitions;
import com.surprising.aeron.service.state.settlement.RuntimeDerivativeMatchProcessor;
import com.surprising.aeron.service.state.settlement.RuntimeSpotMatchProcessor;
import com.surprising.aeron.service.state.snapshot.RuntimeStateMaterializer;
import com.surprising.aeron.service.state.snapshot.RuntimeStateProjector;
import exchange.core2.core.common.MatcherResult.MatcherEvent;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Test fixture that drives the production mutable runtime and materializes only at assertion boundaries. */
public final class RuntimeTestStateTransitions {

    public RuntimeTestStateTransitions() {
    }

    public RuntimeTestStateTransitions(LaneTopology ignored) {
    }

    public TradingCoreState registerInstrument(TradingCoreState before, RegisterInstrumentCommand command) {
        var instrument = com.surprising.aeron.service.state.instrument.CoreInstrument.from(
                before.productLine(), command);
        if (before.instruments().containsKey(instrument.instrumentId())) {
            throw new com.surprising.aeron.service.exception.CoreStateRejectedException(
                    "INVALID_COMMAND", "instrument is already registered");
        }
        var instruments = new java.util.HashMap<>(before.instruments());
        instruments.put(instrument.instrumentId(), instrument);
        return new TradingCoreState(before.productLine(), Math.incrementExact(before.revision()),
                before.users(), before.orders(), instruments, before.riskState(), before.treasuryState(),
                before.leverages(), before.algoOrders(), before.cancelAllAfterTimers(),
                before.clientOrderIndex(), before.triggerOrders());
    }

    public TradingCoreState adjustBalance(TradingCoreState before, long userId, BalanceAdjustmentCommand command) {
        return mutate(before, (runtime, identities) ->
                RuntimeAccountStateTransitions.adjustBalance(runtime, identities, userId, command));
    }

    public TradingCoreState updateRiskScanControl(TradingCoreState before, UpdateRiskScanControlCommand command,
                                           long updatedAtEpochMillis) {
        return mutate(before, (runtime, identities) ->
                RuntimeRiskStateTransitions.updateScanControl(runtime, command, updatedAtEpochMillis));
    }

    public TradingCoreState updatePositionMode(TradingCoreState before, long userId, UpdatePositionModeCommand command) {
        return mutate(before, (runtime, identities) ->
                DerivativeAccountCommandProcessor.updatePositionMode(runtime, userId, command));
    }

    public TradingCoreState updateLeverage(TradingCoreState before, long userId, UpdateLeverageCommand command) {
        return mutate(before, (runtime, identities) ->
                DerivativeAccountCommandProcessor.updateLeverage(runtime, identities, userId, command));
    }

    public TradingCoreState adjustPositionMargin(TradingCoreState before, long userId,
                                          AdjustPositionMarginCommand command) {
        return mutate(before, (runtime, identities) ->
                DerivativeAccountCommandProcessor.adjustPositionMargin(runtime, identities, userId, command));
    }

    public TradingCoreState applyMarkPrice(TradingCoreState before, ApplyMarkPriceCommand command) {
        return mutate(before, (runtime, identities) -> RuntimeDerivativeRiskProcessor.applyMarkPrice(
                before, command, before.users().keySet(), runtime, identities));
    }

    public TradingCoreState continueRiskScan(TradingCoreState before, int maxUsers) {
        return mutate(before, (runtime, identities) -> RuntimeDerivativeRiskProcessor.applyContinuation(
                before, maxUsers, before.users().keySet(), runtime, identities));
    }

    public TradingCoreState placeOrder(TradingCoreState before, long userId, PlaceOrderCommand command) {
        return placeOrder(before, userId, command, UUID.randomUUID(), 0, new ActiveOrderIndex(before));
    }

    public TradingCoreState placeOrder(TradingCoreState before, long userId, ResolvedPlaceOrder command) {
        return mutate(before, (runtime, identities) -> {
            ActiveOrderIndex activeOrders = new ActiveOrderIndex(before);
            long required = RuntimeOrderAdmission.requiredReservation(runtime, identities, userId,
                    command, 0, runtime.admissionOrders(userId, identities.symbolId(command.instrumentId())));
            RuntimeOrderStateTransitions.place(runtime, identities, userId, command, UUID.randomUUID(), required);
        });
    }

    public TradingCoreState placeOrder(TradingCoreState before, long userId, PlaceOrderCommand command,
                                UUID commandId, long openInterestSteps, ActiveOrderIndex activeOrders) {
        return mutate(before, (runtime, identities) -> {
            ResolvedPlaceOrder resolved = CoreOrderDecisionResolver.resolve(before, command);
            long required = RuntimeOrderAdmission.requiredReservation(runtime, identities, userId,
                    resolved, openInterestSteps, runtime.admissionOrders(userId, identities.symbolId(resolved.instrumentId())));
            RuntimeOrderStateTransitions.place(runtime, identities, userId, resolved, commandId, required);
        });
    }

    public TradingCoreState cancelOrder(TradingCoreState before, long userId, CancelOrderCommand command) {
        return mutate(before, (runtime, identities) ->
                RuntimeOrderStateTransitions.cancel(runtime, userId, command.orderId()));
    }

    public TradingCoreState upsertAlgoOrder(TradingCoreState before, long userId, CoreAlgoOrderView view) {
        return mutate(before, (runtime, identities) ->
                RuntimeAlgoOrderStateTransitions.upsert(runtime, identities, userId, view));
    }

    public TradingCoreState updateCancelAllAfter(TradingCoreState before, long userId,
                                          CoreCancelAllAfterCommand command) {
        return mutate(before, (runtime, identities) ->
                RuntimeCancelAllAfterStateTransitions.update(runtime, userId, command));
    }

    public TradingCoreState applyMatches(TradingCoreState before, long takerOrderId,
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

    public FundingApplication applyFundingWithFacts(TradingCoreState before, ApplyFundingCommand command) {
        return applyFundingWithFacts(before, command, null, null);
    }

    public FundingApplication applyFundingWithFacts(TradingCoreState before, ApplyFundingCommand command,
                                              Iterable<Long> userIds, UUID chunkCommandId) {
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        try (TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities)) {
            var result = RuntimePerpetualFundingProcessor.apply(before, command,
                    userIds, chunkCommandId, runtime, identities);
            return new FundingApplication(RuntimeStateMaterializer.materialize(runtime, identities),
                    result.payments(), result.progress());
        }
    }

    public TradingCoreState applyFunding(TradingCoreState before, ApplyFundingCommand command) {
        return applyFundingWithFacts(before, command).state();
    }

    public SettlementApplication settleInstrumentWithProgress(TradingCoreState before,
                                                        SettleInstrumentCommand command,
                                                        Iterable<Long> userIds, UUID chunkCommandId) {
        return settleInstrumentWithProgress(before, command, userIds, chunkCommandId, null);
    }

    public SettlementApplication settleInstrumentWithProgress(TradingCoreState before,
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

    public TradingCoreState settleInstrument(TradingCoreState before, SettleInstrumentCommand command) {
        return settleInstrumentWithProgress(before, command, null, null, null).state();
    }

    public static java.util.List<com.surprising.aeron.service.matching.DeterministicExchangeCoreAdapter.CancellationOrder> cancellationRoutes(
            Collection<CoreOrderState> orders) {
        return orders.stream().map(order -> new com.surprising.aeron.service.matching.DeterministicExchangeCoreAdapter.CancellationOrder(
                order.orderId(), order.userId(), order.instrumentId())).toList();
    }

    public TradingCoreState executeLiquidation(TradingCoreState before, ExecuteLiquidationCommand command) {
        return mutate(before, (runtime, identities) -> RuntimeDerivativeLiquidationProcessor.applyExecution(
                before, command, cancellationRoutes(openOrders(before)), runtime, identities));
    }

    public TradingCoreState executeLiquidationAfterCancellation(TradingCoreState before,
                                                          ExecuteLiquidationCommand command) {
        return mutate(before, (runtime, identities) -> RuntimeDerivativeLiquidationProcessor.applyExecution(
                before, command, List.of(), runtime, identities));
    }

    public TradingCoreState advanceLiquidationCancellation(TradingCoreState before,
                                                     ExecuteLiquidationCommand command,
                                                     Collection<CoreOrderState> canceledOrders,
                                                     long nextCursorOrderId) {
        return mutate(before, (runtime, identities) ->
                RuntimeDerivativeLiquidationProcessor.applyCancellationAdvance(before, command,
                        cancellationRoutes(canceledOrders), nextCursorOrderId, runtime, identities));
    }

    public TradingCoreState cancelLifecycleOrders(TradingCoreState before,
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

    public TradingCoreState resolveLiquidation(TradingCoreState before, ResolveLiquidationCommand command) {
        return mutate(before, (runtime, identities) ->
                RuntimeLiquidationResolution.apply(before, command, runtime, identities));
    }

    public TradingCoreState adjustInsuranceFund(TradingCoreState before, AdjustInsuranceFundCommand command) {
        return mutate(before, (runtime, identities) ->
                RuntimeInsuranceFundStateTransitions.adjust(runtime, identities, command));
    }

    public TradingCoreState executeAdl(TradingCoreState before, ExecuteAdlCommand command) {
        return mutate(before, (runtime, identities) -> RuntimeAdlExecution.apply(before, command, runtime, identities));
    }

    public List<CoreAdlCandidateView> adlCandidates(TradingCoreState before, String asset, int limit) {
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        try (TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities)) {
            return RuntimeRiskQueryService.adlCandidates(runtime, identities, asset,
                    new AdlPositionIndex(before, identities).positions(asset), limit);
        }
    }

    public List<CoreRiskSnapshotView> riskSnapshots(TradingCoreState before, long userId) {
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
        public void apply(TradingRuntimeState runtime, RuntimeIdentityRegistry identities);
    }

    public record FundingApplication(TradingCoreState state, List<CoreFundingPaymentView> payments,
                              CoreFundingProgressView progress) {
    }

    public record SettlementApplication(TradingCoreState state, CoreSettlementProgressView progress) {
    }
}
