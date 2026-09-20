package com.surprising.aeron.service.state;

import com.surprising.aeron.service.state.math.*;

import com.surprising.aeron.service.state.index.ActiveOrderIndex;
import com.surprising.aeron.service.state.index.AlgoOrderIndex;
import com.surprising.aeron.service.state.index.LiquidationIndex;
import com.surprising.aeron.service.state.index.TriggerOrderIndex;

import com.surprising.aeron.service.state.model.CoreAlgoOrderState;
import com.surprising.aeron.service.state.model.CoreLeverageKey;
import com.surprising.aeron.service.state.model.CoreLiquidationState;
import com.surprising.aeron.service.state.model.CoreOrderState;
import com.surprising.aeron.service.state.model.CoreRiskState;
import com.surprising.aeron.service.state.model.CoreTriggerOrderState;

import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CancelOrderCommand;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.protocol.RegisterInstrumentCommand;
import com.surprising.aeron.protocol.ApplyMarkPriceCommand;
import com.surprising.aeron.protocol.ApplyFundingCommand;
import com.surprising.aeron.protocol.SettleInstrumentCommand;
import com.surprising.aeron.protocol.ExecuteLiquidationCommand;
import com.surprising.aeron.protocol.ResolveLiquidationCommand;
import com.surprising.aeron.protocol.AdjustPositionMarginCommand;
import com.surprising.aeron.protocol.UpdatePositionModeCommand;
import com.surprising.aeron.protocol.UpdateRiskScanControlCommand;
import com.surprising.aeron.protocol.CoreRiskScanControlView;
import com.surprising.aeron.protocol.UpdateLeverageCommand;
import com.surprising.aeron.protocol.CoreTriggerOrderStateView;
import exchange.core2.core.common.MatcherResult.MatcherEvent;
import com.surprising.aeron.service.state.TradingCoreState.ClientOrderKey;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class TradingCoreReducer {

    private final RiskScanExecution riskScanExecution;

    public TradingCoreReducer() {
        this(LaneTopology.configured(Boolean.getBoolean("surprising.aeron.p10-characterization")));
    }

    TradingCoreReducer(LaneTopology topology) {
        this.riskScanExecution = new RiskScanExecution(topology);
    }

    public TradingCoreState upsertTriggerOrder(TradingCoreState state, long userId,
                                               CoreTriggerOrderStateView view) {
        return TriggerOrderStateTransitions.upsertTriggerOrder(state, userId, view);
    }

    public TradingCoreState upsertTriggerOrder(TradingCoreState state, long userId,
                                               CoreTriggerOrderStateView view,
                                               TriggerOrderIndex triggerOrderIndex) {
        return TriggerOrderStateTransitions.upsertTriggerOrder(state, userId, view, triggerOrderIndex);
    }

    public TradingCoreState cancelTriggerOrder(TradingCoreState state, long userId, long triggerOrderId) {
        return TriggerOrderStateTransitions.cancelTriggerOrder(state, userId, triggerOrderId);
    }

    public TradingCoreState claimTriggerOrder(TradingCoreState state, long triggerOrderId, long triggerSequence,
                                              long triggeredPriceTicks, long triggeredAtEpochMillis) {
        return TriggerOrderStateTransitions.claimTriggerOrder(state, triggerOrderId, triggerSequence,
                triggeredPriceTicks, triggeredAtEpochMillis);
    }

    public TradingCoreState completeTriggerOrder(TradingCoreState state, long triggerOrderId, boolean success,
                                                 long placedOrderId, String rejectReason, long completedAtEpochMillis) {
        return TriggerOrderStateTransitions.completeTriggerOrder(state, triggerOrderId, success, placedOrderId,
                rejectReason, completedAtEpochMillis);
    }

    public TradingCoreState updateTriggerTrailing(TradingCoreState state, long triggerOrderId,
                                                  long highestPriceTicks, long lowestPriceTicks,
                                                  long activatedAtEpochMillis) {
        return TriggerOrderStateTransitions.updateTriggerTrailing(state, triggerOrderId, highestPriceTicks,
                lowestPriceTicks, activatedAtEpochMillis);
    }

    public TradingCoreState expireTriggerOrder(TradingCoreState state, long triggerOrderId,
                                               long expiredAtEpochMillis) {
        return TriggerOrderStateTransitions.expireTriggerOrder(state, triggerOrderId, expiredAtEpochMillis);
    }

    public TradingCoreState retryTriggerOrder(TradingCoreState state, long triggerOrderId,
                                              long staleBeforeEpochMillis, long retryAtEpochMillis) {
        return TriggerOrderStateTransitions.retryTriggerOrder(state, triggerOrderId, staleBeforeEpochMillis,
                retryAtEpochMillis);
    }

    public TradingCoreState updateCancelAllAfter(
            TradingCoreState state,
            long userId,
            com.surprising.aeron.protocol.CoreCancelAllAfterCommand command) {
        return CancelAllAfterStateTransitions.update(state, userId, command);
    }

    public TradingCoreState upsertAlgoOrder(TradingCoreState state, long userId,
                                             com.surprising.aeron.protocol.CoreAlgoOrderView view) {
        return AlgoOrderStateTransitions.upsert(state, userId, view);
    }

    public TradingCoreState upsertAlgoOrder(TradingCoreState state, long userId,
                                             com.surprising.aeron.protocol.CoreAlgoOrderView view,
                                             AlgoOrderIndex algoOrderIndex) {
        return AlgoOrderStateTransitions.upsert(state, userId, view, algoOrderIndex);
    }

    public java.util.List<com.surprising.aeron.protocol.CoreRiskSnapshotView> riskSnapshots(
            TradingCoreState state, long userId) {
        return riskSnapshots(state, userId, state.riskState().snapshots().keySet());
    }

    public java.util.List<com.surprising.aeron.protocol.CoreRiskSnapshotView> riskSnapshots(
            TradingCoreState state, long userId, java.util.Set<String> snapshotKeys) {
        return RiskSnapshotQueries.find(state, userId, snapshotKeys);
    }

    public TradingCoreState updateLeverage(TradingCoreState state, long userId, UpdateLeverageCommand command) {
        return LeverageStateTransitions.update(state, userId, command);
    }

    public TradingCoreState updatePositionMode(
            TradingCoreState state, long userId, UpdatePositionModeCommand command) {
        return PositionStateTransitions.updateMode(state, userId, command);
    }

    public TradingCoreState adjustPositionMargin(
            TradingCoreState state, long userId, AdjustPositionMarginCommand command) {
        return PositionStateTransitions.adjustMargin(state, userId, command);
    }

    public TradingCoreState adjustBalance(
            TradingCoreState state,
            long userId,
            BalanceAdjustmentCommand command) {
        return BalanceStateTransitions.adjust(state, userId, command);
    }

    public TradingCoreState placeOrder(TradingCoreState state, long userId, PlaceOrderCommand command) {
        return OrderStateTransitions.placeOrder(state, userId, command);
    }

    public TradingCoreState placeOrder(TradingCoreState state, long userId, ResolvedPlaceOrder command) {
        return OrderStateTransitions.placeOrder(state, userId, command);
    }

    public TradingCoreState placeOrder(
            TradingCoreState state,
            long userId,
            PlaceOrderCommand command,
            UUID commandId) {
        return OrderStateTransitions.placeOrder(state, userId, command, commandId);
    }

    public TradingCoreState placeOrder(
            TradingCoreState state,
            long userId,
            PlaceOrderCommand command,
            UUID commandId,
            long indexedOpenInterestSteps) {
        return OrderStateTransitions.placeOrder(state, userId, command, commandId, indexedOpenInterestSteps);
    }

    public TradingCoreState placeOrder(
            TradingCoreState state,
            long userId,
            PlaceOrderCommand command,
            UUID commandId,
            long indexedOpenInterestSteps,
            ActiveOrderIndex activeOrderIndex) {
        return OrderStateTransitions.placeOrder(state, userId, command, commandId,
                indexedOpenInterestSteps, activeOrderIndex);
    }

    public TradingCoreState placeOrder(
            TradingCoreState state,
            long userId,
            ResolvedPlaceOrder command,
            UUID commandId,
            long indexedOpenInterestSteps,
            ActiveOrderIndex activeOrderIndex) {
        return OrderStateTransitions.placeOrder(state, userId, command, commandId,
                indexedOpenInterestSteps, activeOrderIndex);
    }

    public long requiredReservationForAcceptedPlaceOrder(
            TradingCoreState state,
            long userId,
            PlaceOrderCommand command,
            long indexedOpenInterestSteps,
            ActiveOrderIndex activeOrderIndex) {
        return OrderStateTransitions.requiredReservationForAcceptedPlaceOrder(state, userId, command,
                indexedOpenInterestSteps, activeOrderIndex);
    }

    public long requiredReservationForAcceptedPlaceOrder(
            TradingCoreState state,
            long userId,
            ResolvedPlaceOrder command,
            long indexedOpenInterestSteps,
            ActiveOrderIndex activeOrderIndex) {
        return OrderStateTransitions.requiredReservationForAcceptedPlaceOrder(state, userId, command,
                indexedOpenInterestSteps, activeOrderIndex);
    }

    public TradingCoreState cancelOrder(TradingCoreState state, long userId, CancelOrderCommand command) {
        return OrderStateTransitions.cancelOrder(state, userId, command);
    }

    public TradingCoreState rejectPlaceOrder(TradingCoreState state, long userId, long orderId) {
        return OrderStateTransitions.rejectPlaceOrder(state, userId, orderId);
    }

    public TradingCoreState pruneAcknowledgedTerminalReservations(
            TradingCoreState state, Collection<Long> acknowledgedOrderIds) {
        return OrderStateTransitions.pruneAcknowledgedTerminalReservations(state, acknowledgedOrderIds);
    }

    public TradingCoreState pruneTerminalState(TradingCoreState state, TerminalPruneBatch batch) {
        if (state == null || batch == null) throw new IllegalArgumentException("terminal prune batch is required");
        if (batch.isEmpty()) return state;
        Map<Long, CoreUserState> users = StateMapSupport.delta(state.users());
        Map<Long, CoreOrderState> orders = StateMapSupport.delta(state.orders());
        Map<ClientOrderKey, Long> clientOrderIndex = StateMapSupport.delta(state.clientOrderIndex());
        Map<Long, CoreAlgoOrderState> algoOrders = StateMapSupport.delta(state.algoOrders());
        Map<Long, CoreTriggerOrderState> triggerOrders = StateMapSupport.delta(state.triggerOrders());
        Map<Long, CoreLiquidationState> liquidations = StateMapSupport.delta(state.riskState().liquidations());

        for (long orderId : batch.orderIds()) {
            CoreOrderState order = orders.get(orderId);
            if (order == null || !order.status().terminal()) {
                throw new IllegalStateException("order is not terminal: " + orderId);
            }
            CoreUserState user = users.get(order.userId());
            OrderReservation reservation = user == null ? null : user.reservations().get(orderId);
            if (reservation != null) {
                if (reservation.remainingUnits() != 0) {
                    throw new IllegalStateException("terminal order retains funded reservation: " + orderId);
                }
                Map<Long, OrderReservation> reservations = StateMapSupport.delta(user.reservations());
                reservations.remove(orderId);
                users.put(user.userId(), user.transition(user.revision(),
                        user.balances(), reservations, user.positions(), user.positionMode()));
            }
            orders.remove(orderId);
            if (!order.clientOrderId().isEmpty()) {
                ClientOrderKey key = new ClientOrderKey(order.userId(), order.clientOrderId());
                if (Long.valueOf(orderId).equals(clientOrderIndex.get(key))) clientOrderIndex.remove(key);
            }
        }
        for (long algoOrderId : batch.algoOrderIds()) {
            CoreAlgoOrderState algo = algoOrders.get(algoOrderId);
            if (algo == null || !algo.terminal()) {
                throw new IllegalStateException("algo order is not terminal: " + algoOrderId);
            }
            algoOrders.remove(algoOrderId);
        }
        for (long triggerOrderId : batch.triggerOrderIds()) {
            CoreTriggerOrderState trigger = triggerOrders.get(triggerOrderId);
            if (trigger == null || trigger.status().open()) {
                throw new IllegalStateException("trigger order is not terminal: " + triggerOrderId);
            }
            triggerOrders.remove(triggerOrderId);
        }
        for (long liquidationId : batch.liquidationIds()) {
            CoreLiquidationState liquidation = liquidations.get(liquidationId);
            if (liquidation == null || !liquidation.terminal()) {
                throw new IllegalStateException("liquidation is not terminal: " + liquidationId);
            }
            liquidations.remove(liquidationId);
        }
        CoreRiskState risk = new CoreRiskState(state.riskState().markPrices(), state.riskState().snapshots(),
                liquidations, state.riskState().scans(), state.riskState().nextLiquidationId(),
                state.riskState().scanControl(), state.riskState().marketRevision());
        return new TradingCoreState(state.productLine(), state.revision(), users, orders,
                state.instruments(), risk, state.treasuryState(), state.leverages(), algoOrders,
                state.cancelAllAfterTimers(), clientOrderIndex, triggerOrders);
    }

    public TradingCoreState applyMatches(
            TradingCoreState state,
            long takerOrderId,
            String baseAsset,
            String quoteAsset,
            List<MatcherEvent> matches) {
        return MatchStateTransitions.apply(state, takerOrderId, baseAsset, quoteAsset, matches);
    }

    public TradingCoreState registerInstrument(TradingCoreState state, RegisterInstrumentCommand command) {
        return InstrumentStateTransitions.register(state, command);
    }

    public TradingCoreState applyMarkPrice(TradingCoreState state, ApplyMarkPriceCommand command) {
        return applyMarkPrice(state, command, null);
    }

    public TradingCoreState applyMarkPrice(TradingCoreState state, ApplyMarkPriceCommand command,
                                           LiquidationIndex liquidationIndex) {
        return applyMarkPrice(state, command, null, liquidationIndex);
    }

    public TradingCoreState applyMarkPrice(TradingCoreState state, ApplyMarkPriceCommand command,
                                           PositionUserIndex positionUserIndex,
                                           LiquidationIndex liquidationIndex) {
        TradingCoreState withMark = MarkPriceStateTransitions.apply(state, command);
        CoreRiskScanControlView scanControl = withMark.riskState().scanControl();
        return scanControl.enabled()
                ? continueRiskScan(withMark, scanControl.scanBatchSize(), positionUserIndex, liquidationIndex)
                : withMark;
    }

    public TradingCoreState continueRiskScan(TradingCoreState state, int maxUsers) {
        return continueRiskScan(state, maxUsers, null);
    }

    public TradingCoreState continueRiskScan(TradingCoreState state, int maxUsers,
                                             LiquidationIndex liquidationIndex) {
        return continueRiskScan(state, maxUsers, null, liquidationIndex);
    }

    public TradingCoreState continueRiskScan(TradingCoreState state, int maxUsers,
                                             PositionUserIndex positionUserIndex,
                                             LiquidationIndex liquidationIndex) {
        return riskScanExecution.continueScan(state, maxUsers, positionUserIndex, liquidationIndex);
    }

    public TradingCoreState updateRiskScanControl(TradingCoreState state,
                                                  UpdateRiskScanControlCommand command,
                                                  long updatedAtEpochMillis) {
        return RiskScanControlStateTransitions.update(state, command, updatedAtEpochMillis);
    }

    public TradingCoreState applyFunding(TradingCoreState state, ApplyFundingCommand command) {
        return applyFundingWithFacts(state, command).state();
    }

    public FundingApplication applyFundingWithFacts(TradingCoreState state, ApplyFundingCommand command) {
        return applyFundingWithFacts(state, command, null);
    }

    public FundingApplication applyFundingWithFacts(TradingCoreState state, ApplyFundingCommand command,
                                                    Iterable<Long> indexedUserIds) {
        return applyFundingWithFacts(state, command, indexedUserIds, null);
    }

    public FundingApplication applyFundingWithFacts(TradingCoreState state, ApplyFundingCommand command,
                                                    Iterable<Long> indexedUserIds, UUID chunkCommandId) {
        return FundingStateTransitions.apply(state, command, indexedUserIds, chunkCommandId);
    }

    public record FundingApplication(TradingCoreState state,
                                     java.util.List<com.surprising.aeron.protocol.CoreFundingPaymentView> payments,
                                     com.surprising.aeron.protocol.CoreFundingProgressView progress) {
        public FundingApplication {
            payments = java.util.List.copyOf(payments);
            if (progress == null) throw new IllegalArgumentException("funding progress is required");
        }
    }

    public TradingCoreState settleInstrument(TradingCoreState state, SettleInstrumentCommand command) {
        return settleInstrument(state, command, null);
    }

    public TradingCoreState settleInstrument(TradingCoreState state, SettleInstrumentCommand command,
                                             Iterable<Long> indexedUserIds) {
        return settleInstrumentWithProgress(state, command, indexedUserIds, null).state();
    }

    public SettlementApplication settleInstrumentWithProgress(TradingCoreState state,
                                                              SettleInstrumentCommand command,
                                                              Iterable<Long> indexedUserIds,
                                                              UUID chunkCommandId) {
        return settleInstrumentWithProgress(state, command, indexedUserIds, chunkCommandId, null);
    }

    public SettlementApplication settleInstrumentWithProgress(TradingCoreState state,
                                                              SettleInstrumentCommand command,
                                                              Iterable<Long> indexedUserIds,
                                                              UUID chunkCommandId,
                                                              ActiveOrderIndex activeOrderIndex) {
        return SettlementStateTransitions.apply(state, command, indexedUserIds, chunkCommandId, activeOrderIndex);
    }

    public TradingCoreState cancelLifecycleOrders(TradingCoreState state, Collection<CoreOrderState> orders) {
        return OrderStateTransitions.cancelOrders(state, orders == null ? List.of() : List.copyOf(orders));
    }

    public TradingCoreState advanceLiquidationCancellation(TradingCoreState state,
                                                            ExecuteLiquidationCommand command,
                                                            Collection<CoreOrderState> orders,
                                                            long nextCursorOrderId) {
        return LiquidationExecution.advanceCancellation(state, command, orders, nextCursorOrderId);
    }

    public TradingCoreState advanceSettlementOrderCancellation(TradingCoreState state,
                                                                SettleInstrumentCommand command,
                                                                Collection<CoreOrderState> orders,
                                                                long nextCursorOrderId,
                                                                UUID chunkCommandId) {
        return SettlementStateTransitions.advanceOrderCancellation(state, command, orders,
                nextCursorOrderId, chunkCommandId);
    }

    public record SettlementApplication(TradingCoreState state,
                                         com.surprising.aeron.protocol.CoreSettlementProgressView progress) {
        public SettlementApplication {
            if (progress == null) throw new IllegalArgumentException("settlement progress is required");
        }
    }

    public TradingCoreState executeLiquidation(TradingCoreState state, ExecuteLiquidationCommand command) {
        return LiquidationExecution.execute(state, command, true);
    }

    public TradingCoreState executeLiquidationAfterCancellation(TradingCoreState state,
                                                                ExecuteLiquidationCommand command) {
        return LiquidationExecution.execute(state, command, false);
    }

    public boolean isLiquidationExecutable(TradingCoreState state, ExecuteLiquidationCommand command) {
        return LiquidationExecution.isExecutable(state, command);
    }

    public TradingCoreState resolveLiquidation(TradingCoreState state, ResolveLiquidationCommand command) {
        return LiquidationResolution.resolve(state, command);
    }

    public TradingCoreState adjustInsuranceFund(TradingCoreState state,
                                                com.surprising.aeron.protocol.AdjustInsuranceFundCommand command) {
        return InsuranceFundStateTransitions.adjust(state, command);
    }

    public TradingCoreState executeAdl(TradingCoreState state,
                                       com.surprising.aeron.protocol.ExecuteAdlCommand command) {
        return AdlExecution.execute(state, command);
    }

    public java.util.List<com.surprising.aeron.protocol.CoreAdlCandidateView> adlCandidates(
            TradingCoreState state, String asset, int limit) {
        return adlCandidates(state, asset, limit, null);
    }

    public java.util.List<com.surprising.aeron.protocol.CoreAdlCandidateView> adlCandidates(
            TradingCoreState state, String asset, int limit, AdlPositionIndex index) {
        return AdlCandidateQueries.find(state, asset, limit, index);
    }

    static TradingCoreState replaceUser(
            TradingCoreState state,
            CoreUserState user,
            Map<Long, CoreOrderState> orders) {
        return replaceUser(state, user, orders, StateMapSupport.delta(state.clientOrderIndex()));
    }

    private static TradingCoreState replaceUser(
            TradingCoreState state,
            CoreUserState user,
            Map<Long, CoreOrderState> orders,
            Map<ClientOrderKey, Long> clientOrderIndex) {
        Map<Long, CoreUserState> users = StateMapSupport.delta(state.users());
        users.put(user.userId(), user);
        Map<Long, CoreOrderState> nextOrders = StateMapSupport.isDelta(orders)
                ? orders : StateMapSupport.delta(orders);
        Map<ClientOrderKey, Long> nextClientOrderIndex = clientOrderIndex == null
                ? StateMapSupport.delta(state.clientOrderIndex()) : clientOrderIndex;
        if (clientOrderIndex != null) {
            return new TradingCoreState(state.productLine(), Math.incrementExact(state.revision()), users, nextOrders,
                    state.instruments(), state.riskState(), state.treasuryState(),
                    state.leverages(), state.algoOrders(), state.cancelAllAfterTimers(), nextClientOrderIndex,
                    state.triggerOrders());
        }
        return new TradingCoreState(state.productLine(), Math.incrementExact(state.revision()), users, nextOrders,
                state.instruments(), state.riskState(), state.treasuryState(),
                state.leverages(), state.algoOrders(), state.cancelAllAfterTimers(), nextClientOrderIndex,
                state.triggerOrders());
    }

}
