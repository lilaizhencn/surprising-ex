package com.surprising.aeron.service.state;

import com.surprising.aeron.service.state.math.*;

import com.surprising.aeron.service.business.ProductTradingRules;
import com.surprising.aeron.service.business.ProductTradingRulesRegistry;

import com.surprising.aeron.service.state.index.ActiveOrderIndex;
import com.surprising.aeron.service.state.index.AlgoOrderIndex;
import com.surprising.aeron.service.state.index.LiquidationIndex;
import com.surprising.aeron.service.state.index.TriggerOrderIndex;

import com.surprising.aeron.service.state.model.AssetBalance;
import com.surprising.aeron.service.state.model.CoreAlgoOrderState;
import com.surprising.aeron.service.state.model.CoreLeverageKey;
import com.surprising.aeron.service.state.model.CoreLiquidationState;
import com.surprising.aeron.service.state.model.CoreMarkPriceState;
import com.surprising.aeron.service.state.model.CoreOrderState;
import com.surprising.aeron.service.state.model.CoreOrderStatus;
import com.surprising.aeron.service.state.model.CorePositionState;
import com.surprising.aeron.service.state.model.CoreRiskState;
import com.surprising.aeron.service.state.model.CoreTriggerOrderState;

import static com.surprising.aeron.service.state.ReducerDerivativeSettlement.*;
import static com.surprising.aeron.service.state.ReducerSpotSettlement.*;
import static com.surprising.aeron.service.state.ReducerSettlementSupport.*;
import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CancelOrderCommand;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.protocol.UpsertInstrumentCommand;
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
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherResult.MatcherEvent;
import com.surprising.instrument.api.math.PerpetualContractMath;
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

    private static final long PPM = 1_000_000L;

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
        if (matches == null) {
            throw new IllegalArgumentException("matches are required");
        }
        if (matches.isEmpty()) {
            CoreOrderState taker = requireOpenOrder(state.orders(), takerOrderId);
            if (!taker.timeInForce().immediate()
                    && taker.orderType() != com.surprising.aeron.protocol.CoreOrderType.MARKET) {
                return state;
            }
        }
        Map<Long, CoreUserState> users = StateMapSupport.delta(state.users());
        Map<Long, CoreOrderState> orders = StateMapSupport.delta(state.orders());
        CoreTreasuryState treasury = state.treasuryState();
        CoreOrderState taker = requireOpenOrder(orders, takerOrderId);
        CoreInstrumentState instrument = requireInstrument(state, taker.symbol(), taker.instrumentChangeId());
        CoreMarkPriceState riskMark = state.productLine().isDerivative()
                ? state.riskState().markPrices().get(instrument.symbol()) : null;
        if (instrument.contractType().isOption() && (riskMark == null
                || riskMark.indexPriceTicks() <= 0 || riskMark.forwardPriceTicks() <= 0)) {
            throw new CoreStateRejectedException("OPTION_RISK_PRICE_MISSING",
                    "option fill requires index and same-expiry forward prices");
        }
        for (MatcherEvent match : matches) {
            if (match.eventType() != MatcherEventType.TRADE) continue;
            CoreOrderState maker = requireOpenOrder(orders, match.matchedOrderId());
            if (!taker.symbol().equals(maker.symbol()) || taker.side() == maker.side()
                    || maker.userId() != match.matchedOrderUid()) {
                throw new IllegalStateException("exchange-core match does not match authoritative orders");
            }
            if (taker.userId() == maker.userId()) {
                throw new CoreStateRejectedException("SELF_TRADE_PREVENTED", "self trade is not allowed");
            }
            if (state.productLine().isDerivative()) {
                long takerLeverage = state.leverages().getOrDefault(
                        new CoreLeverageKey(taker.userId(), instrument.symbol(), taker.marginMode()),
                        instrument.maxLeveragePpm());
                DerivativeFillResult takerFill = applyDerivativeFill(users.get(taker.userId()), taker,
                        instrument, riskMark, match.price(), match.size(), true, takerLeverage, treasury);
                users.put(taker.userId(), takerFill.user());
                treasury = takerFill.treasury();
                long makerLeverage = state.leverages().getOrDefault(
                        new CoreLeverageKey(maker.userId(), instrument.symbol(), maker.marginMode()),
                        instrument.maxLeveragePpm());
                DerivativeFillResult makerFill = applyDerivativeFill(users.get(maker.userId()), maker,
                        instrument, riskMark, match.price(), match.size(), false, makerLeverage, treasury);
                users.put(maker.userId(), makerFill.user());
                treasury = makerFill.treasury();
            } else {
                CoreOrderState buyerOrder = taker.side() == CoreOrderSide.BUY ? taker : maker;
                CoreOrderState sellerOrder = taker.side() == CoreOrderSide.SELL ? taker : maker;
                long buyerFeeRate = buyerOrder.orderId() == taker.orderId()
                        ? buyerOrder.takerFeeRatePpm() : buyerOrder.makerFeeRatePpm();
                long sellerFeeRate = sellerOrder.orderId() == taker.orderId()
                        ? sellerOrder.takerFeeRatePpm() : sellerOrder.makerFeeRatePpm();
                SpotFillResult buyerFill = applySpotFill(users.get(buyerOrder.userId()), buyerOrder,
                        instrument, AssetBalance.normalizeAsset(baseAsset), AssetBalance.normalizeAsset(quoteAsset),
                        match.price(), match.size(), buyerFeeRate, treasury);
                users.put(buyerOrder.userId(), buyerFill.user());
                treasury = buyerFill.treasury();
                SpotFillResult sellerFill = applySpotFill(users.get(sellerOrder.userId()), sellerOrder,
                        instrument, AssetBalance.normalizeAsset(baseAsset), AssetBalance.normalizeAsset(quoteAsset),
                        match.price(), match.size(), sellerFeeRate, treasury);
                users.put(sellerOrder.userId(), sellerFill.user());
                treasury = sellerFill.treasury();
            }
            long takerFeeUnits = Math.negateExact(CoreContractMath.feeDeltaUnits(
                    instrument, match.price(), match.size(), taker.takerFeeRatePpm()));
            long makerFeeUnits = Math.negateExact(CoreContractMath.feeDeltaUnits(
                    instrument, match.price(), match.size(), maker.makerFeeRatePpm()));
            taker = taker.fill(match.size(), takerFeeUnits);
            maker = maker.fill(match.size(), makerFeeUnits);
            orders.put(taker.orderId(), taker);
            orders.put(maker.orderId(), maker);
            if (maker.status() != CoreOrderStatus.OPEN) {
                users.put(maker.userId(), OrderStateTransitions.releaseTerminalReservation(
                        users.get(maker.userId()), maker.orderId()));
            }
        }
        if (taker.status() == CoreOrderStatus.OPEN && !taker.timeInForce().immediate()
                && taker.orderType() != com.surprising.aeron.protocol.CoreOrderType.MARKET) {
        } else {
            if (taker.status() == CoreOrderStatus.OPEN) {
                taker = taker.cancel();
                orders.put(taker.orderId(), taker);
            }
            users.put(taker.userId(), OrderStateTransitions.releaseTerminalReservation(
                    users.get(taker.userId()), taker.orderId()));
        }
        return new TradingCoreState(state.productLine(), Math.incrementExact(state.revision()), users, orders,
                state.instruments(), state.riskState(), treasury, state.leverages(), state.algoOrders(), state.cancelAllAfterTimers(),
                state.clientOrderIndex(), state.triggerOrders());
    }

    public TradingCoreState upsertInstrument(TradingCoreState state, UpsertInstrumentCommand command) {
        return InstrumentStateTransitions.upsert(state, command);
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
        if (!state.productLine().isFundingProduct()) {
            throw new CoreStateRejectedException("PRODUCT_LINE_UNSUPPORTED", "funding requires perpetual product");
        }
        CoreInstrumentState instrument = requireInstrument(state, command.symbol(), command.instrumentChangeId());
        ProductTradingRules kernel = ProductTradingRulesRegistry.forInstrument(instrument);
        long previousSettlement = state.treasuryState().fundingSettlements()
                .getOrDefault(instrument.symbol(), 0L);
        if (command.settlementId() <= previousSettlement) {
            throw new CoreStateRejectedException("STALE_SETTLEMENT_ID", "funding settlement id must increase");
        }
        CoreMarkPriceState mark = state.riskState().markPrices().get(instrument.symbol());
        if (mark == null) {
            throw new CoreStateRejectedException("MARK_PRICE_NOT_FOUND", "funding requires mark price");
        }
        CoreTreasuryState.FundingProgress previousProgress = state.treasuryState()
                .fundingProgress(instrument.symbol());
        long fundingMark = previousProgress == null ? mark.markPriceTicks() : previousProgress.markPriceTicks();
        long fundingPriceSequence = previousProgress == null ? mark.priceSequence() : previousProgress.priceSequence();
        boolean chunked = indexedUserIds != null && chunkCommandId != null;
        if (chunked) {
            if (previousProgress == null && command.cursorUserId() != 0) {
                throw new CoreStateRejectedException("INVALID_COMMAND", "funding cursor must start at zero");
            }
            if (previousProgress != null && (previousProgress.settlementId() != command.settlementId()
                    || previousProgress.instrumentChangeId() != command.instrumentChangeId()
                    || previousProgress.fundingRatePpm() != command.fundingRatePpm()
                    || previousProgress.nextCursorUserId() != command.cursorUserId())) {
                throw new CoreStateRejectedException("INVALID_COMMAND", "funding cursor does not match progress");
            }
        }
        Map<Long, CoreUserState> users = StateMapSupport.delta(state.users());
        CoreTreasuryState treasury = state.treasuryState();
        java.util.ArrayList<com.surprising.aeron.protocol.CoreFundingPaymentView> payments = new java.util.ArrayList<>();
        java.util.ArrayList<Long> selectedUserIds = new java.util.ArrayList<>();
        boolean moreUsers = false;
        if (!chunked) {
            state.users().keySet().forEach(selectedUserIds::add);
        } else {
            for (Long userId : indexedUserIds) {
                if (userId == null || userId <= command.cursorUserId()) continue;
                if (selectedUserIds.size() < command.maxUsers()) {
                    selectedUserIds.add(userId);
                } else {
                    moreUsers = true;
                    break;
                }
            }
        }
        Iterable<Long> userIds = selectedUserIds;
        for (Long userId : userIds) {
            CoreUserState user = state.user(userId);
            if (user == null) continue;
            long delta = 0;
            java.util.List<CorePositionState> positions = positionsForSymbol(user, instrument.symbol());
            java.util.ArrayList<Long> positionDeltas = new java.util.ArrayList<>(positions.size());
            for (CorePositionState position : positions) {
                long positionDelta = kernel.fundingDeltaUnits(instrument,
                        position.signedQuantitySteps(), fundingMark, command.fundingRatePpm());
                positionDeltas.add(positionDelta);
                delta = Math.addExact(delta, positionDelta);
            }
            if (positions.isEmpty()) continue;
            CashResult result = applyCash(requireBalance(user, instrument.settleAsset()), delta);
            if (result.appliedDelta() != 0) {
                Map<String, AssetBalance> balances = StateMapSupport.delta(user.balances());
                balances.put(instrument.settleAsset(), result.balance());
                users.put(user.userId(), user.transition(Math.incrementExact(user.revision()),
                        balances, user.reservations(), user.positions(), user.positionMode()));
                treasury = treasury.adjustFundingResidual(
                        instrument.settleAsset(), Math.negateExact(result.appliedDelta()));
            }
            long debitRelief = Math.subtractExact(result.appliedDelta(), delta);
            for (int index = 0; index < positions.size(); index++) {
                CorePositionState position = positions.get(index);
                long amount = positionDeltas.get(index);
                if (amount < 0 && debitRelief > 0) {
                    long relief = Math.min(Math.negateExact(amount), debitRelief);
                    amount = Math.addExact(amount, relief);
                    debitRelief = Math.subtractExact(debitRelief, relief);
                }
                if (amount != 0) {
                    long notional = com.surprising.instrument.api.math.PerpetualContractMath.notionalUnits(
                            instrument.contractType(), position.signedQuantitySteps(), fundingMark,
                            instrument.notionalMultiplierUnits(), instrument.priceTickUnits(),
                            instrument.settleScaleUnits());
                    payments.add(new com.surprising.aeron.protocol.CoreFundingPaymentView(
                            command.settlementId(), user.userId(), instrument.symbol(), position.marginMode(),
                            position.positionSide(), instrument.settleAsset(), position.signedQuantitySteps(),
                            notional, command.fundingRatePpm(), amount));
                }
            }
            if (debitRelief != 0) throw new IllegalStateException("funding debit relief was not fully allocated");
        }
        boolean complete = !chunked || !moreUsers;
        long nextCursorUserId = complete ? 0 : selectedUserIds.getLast();
        if (complete) {
            treasury = treasury.recordFunding(instrument.symbol(), command.settlementId());
        } else {
            UUID progressCommandId = chunkCommandId == null ? new UUID(0, 0) : chunkCommandId;
            treasury = treasury.withFundingProgress(instrument.symbol(), new CoreTreasuryState.FundingProgress(
                    command.settlementId(), command.instrumentChangeId(), command.fundingRatePpm(),
                    0, nextCursorUserId, progressCommandId, fundingMark, fundingPriceSequence));
        }
        var progress = new com.surprising.aeron.protocol.CoreFundingProgressView(
                command.settlementId(), complete, nextCursorUserId, selectedUserIds.size());
        return new FundingApplication(new TradingCoreState(state.productLine(), Math.incrementExact(state.revision()), users,
                state.orders(), state.instruments(), state.riskState(), treasury,
                state.leverages(), state.algoOrders(), state.cancelAllAfterTimers(), state.clientOrderIndex(),
                state.triggerOrders()), payments,
                progress);
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
        // Simulation uses the same financial kernel as authoritative settlement.
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        TradingRuntimeState runtime = RuntimeStateProjector.project(state, identities);
        try {
            var progress = RuntimeSettlementProcessor.apply(state, command, indexedUserIds, chunkCommandId,
                    activeOrderIndex, runtime, identities);
            if (runtime.revision() == state.revision()) return new SettlementApplication(state, progress);
            return new SettlementApplication(RuntimeStateMaterializer.materialize(runtime, identities), progress);
        } finally {
            runtime.close();
        }
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
        if (nextCursorOrderId <= 0 || chunkCommandId == null) {
            throw new IllegalArgumentException("settlement cursor must advance");
        }
        CoreInstrumentState instrument = requireInstrument(state, command.symbol(), command.instrumentChangeId());
        if (instrument.contractType().isOption()) {
            OptionContractMath.optionSettlementCashUnits(instrument, command.settlementPriceTicks());
        }
        CoreTreasuryState.LifecycleProgress progress = state.treasuryState().lifecycleProgress(command.symbol());
        if (progress != null && (progress.settlementId() != command.settlementId()
                || progress.instrumentChangeId() != command.instrumentChangeId()
                || progress.settlementPriceTicks() != command.settlementPriceTicks()
                || progress.optionCashUnitsPerContract() != command.optionCashUnitsPerContract()
                || progress.ordersComplete() || progress.nextCursorOrderId() != command.cursorOrderId()
                || progress.nextCursorUserId() != command.cursorUserId())) {
            throw new CoreStateRejectedException("INVALID_COMMAND", "settlement cursor does not match progress");
        }
        if (progress == null && (command.cursorOrderId() != 0 || command.cursorUserId() != 0)) {
            throw new CoreStateRejectedException("INVALID_COMMAND", "settlement cursor must start at zero");
        }
        TradingCoreState canceled = cancelLifecycleOrders(state, orders);
        CoreTreasuryState nextTreasury = canceled.treasuryState().withLifecycleProgress(command.symbol(),
                new CoreTreasuryState.LifecycleProgress(command.settlementId(), command.instrumentChangeId(),
                        command.settlementPriceTicks(), command.optionCashUnitsPerContract(), false,
                        nextCursorOrderId, 0, chunkCommandId));
        return withTreasury(canceled, nextTreasury);
    }

    private static TradingCoreState withTreasury(TradingCoreState state, CoreTreasuryState treasury) {
        return new TradingCoreState(state.productLine(), Math.incrementExact(state.revision()), state.users(),
                state.orders(), state.instruments(), state.riskState(), treasury,
                state.leverages(), state.algoOrders(), state.cancelAllAfterTimers(), state.clientOrderIndex(),
                state.triggerOrders());
    }

    private static LifecycleOrderChunk selectLifecycleOrders(TradingCoreState state,
                                                              ActiveOrderIndex activeOrderIndex,
                                                              String symbol, long cursorOrderId, int maxOrders) {
        if (activeOrderIndex == null) activeOrderIndex = new ActiveOrderIndex(state);
        ActiveOrderIndex.Page page = activeOrderIndex.page(0, symbol, cursorOrderId, maxOrders);
        List<CoreOrderState> selected = page.orderIds().stream().map(state::order)
                .filter(order -> order != null && order.status() == CoreOrderStatus.OPEN).toList();
        return new LifecycleOrderChunk(selected, page.nextCursorOrderId() != 0);
    }

    private record LifecycleOrderChunk(List<CoreOrderState> orders, boolean more) {
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
        String normalizedAsset = AssetBalance.normalizeAsset(asset);
        java.util.ArrayList<com.surprising.aeron.protocol.CoreAdlCandidateView> result = new java.util.ArrayList<>();
        Iterable<AdlPositionIndex.PositionKey> keys = index == null
                ? state.users().values().stream().flatMap(user -> user.positions().values().stream()
                        .filter(position -> position.signedQuantitySteps() != 0
                                && position.marginAsset().equals(normalizedAsset))
                        .map(position -> new AdlPositionIndex.PositionKey(user.userId(), position.symbol(),
                                position.positionSide()))).toList()
                : index.positions(normalizedAsset);
        for (AdlPositionIndex.PositionKey key : keys) {
            CoreUserState user = state.user(key.userId());
            CorePositionState position = user == null ? null
                    : user.positions().get(positionKey(key.symbol(), key.positionSide()));
            if (user == null || position == null) continue;
                if (position.signedQuantitySteps() == 0 || !position.marginAsset().equals(normalizedAsset)) continue;
                CoreInstrumentState instrument = state.instruments().get(position.symbol());
                CoreMarkPriceState mark = state.riskState().markPrices().get(position.symbol());
                if (instrument == null || mark == null
                    || !(instrument.contractType().isPerpetual() || instrument.contractType().isDelivery()
                    || instrument.contractType().isOption())
                        || !instrument.settleAsset().equals(normalizedAsset)) continue;
                long profit = CoreContractMath.pnlUnits(instrument, position.signedQuantitySteps(),
                        position.entryPriceTicks(), mark.markPriceTicks());
                if (profit <= 0) continue;
                long notional = com.surprising.instrument.api.math.PerpetualContractMath.notionalUnits(
                        instrument.contractType(), position.signedQuantitySteps(), mark.markPriceTicks(),
                        instrument.notionalMultiplierUnits(), instrument.priceTickUnits(),
                        instrument.settleScaleUnits());
                long margin = position.marginMode() == com.surprising.aeron.protocol.CoreMarginMode.ISOLATED
                        ? position.positionMarginUnits()
                        : user.totalUnits(normalizedAsset);
                long profitRate = ratio(profit, notional);
                long leverage = margin <= 0 ? Long.MAX_VALUE : ratio(notional, margin);
                long priority = multiplyDivideCapped(profitRate, leverage, PPM);
                result.add(new com.surprising.aeron.protocol.CoreAdlCandidateView(user.userId(), position.symbol(),
                        normalizedAsset, position.marginMode(), position.positionSide(),
                        position.signedQuantitySteps(), position.entryPriceTicks(), mark.markPriceTicks(),
                        mark.priceSequence(), notional, profit, margin, profitRate, leverage, priority));
        }
        return result.stream().sorted(java.util.Comparator
                        .comparingLong(com.surprising.aeron.protocol.CoreAdlCandidateView::priorityScorePpm).reversed()
                        .thenComparing(java.util.Comparator.comparingLong(
                                com.surprising.aeron.protocol.CoreAdlCandidateView::unrealizedProfitUnits).reversed())
                        .thenComparingLong(com.surprising.aeron.protocol.CoreAdlCandidateView::userId)
                        .thenComparing(com.surprising.aeron.protocol.CoreAdlCandidateView::symbol))
                .limit(limit).toList();
    }

    private static long ratio(long numerator, long denominator) {
        return numerator <= 0 || denominator <= 0 ? 0 : multiplyDivideCapped(numerator, PPM, denominator);
    }

    private static long multiplyDivideCapped(long left, long right, long divisor) {
        try {
            return Math.multiplyExact(left, right) / divisor;
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private static CoreInstrumentState requireInstrument(TradingCoreState state, String symbol, long version) {
        CoreInstrumentState instrument = state.instruments().get(OrderReservation.normalizeSymbol(symbol));
        if (instrument == null) {
            throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument state is missing");
        }
        if (instrument.changeId() != version) {
            throw new CoreStateRejectedException("INSTRUMENT_CHANGE_ID_CONFLICT", "instrument version differs");
        }
        return instrument;
    }

    private static CoreInstrumentState requireLifecycleInstrument(
            TradingCoreState state, String symbol, long lifecycleVersion) {
        CoreInstrumentState instrument = state.instruments().get(OrderReservation.normalizeSymbol(symbol));
        if (instrument == null) {
            throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument state is missing");
        }
        if (lifecycleVersion < instrument.changeId()) {
            throw new CoreStateRejectedException("INSTRUMENT_CHANGE_ID_CONFLICT",
                    "instrument lifecycle version precedes execution version");
        }
        return instrument;
    }

    private static CoreOrderState requireOpenOrder(Map<Long, CoreOrderState> orders, long orderId) {
        CoreOrderState order = orders.get(orderId);
        if (order == null || order.status() != CoreOrderStatus.OPEN) {
            throw new IllegalStateException("matched order is not open orderId=" + orderId);
        }
        return order;
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

    private static void requireUserId(long userId) {
        if (userId <= 0) {
            throw new CoreStateRejectedException("INVALID_USER_ID", "userId must be positive");
        }
    }

    private static List<CorePositionState> positionsForSymbol(CoreUserState user, String symbol) {
        String normalized = OrderReservation.normalizeSymbol(symbol);
        return user.positions().values().stream()
                .filter(position -> position.symbol().equals(normalized) && position.signedQuantitySteps() != 0)
                .toList();
    }

}
