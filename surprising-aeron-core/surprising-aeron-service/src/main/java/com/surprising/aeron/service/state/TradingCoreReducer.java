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
import com.surprising.aeron.service.state.model.CoreRiskSnapshot;
import com.surprising.aeron.service.state.model.CoreRiskState;
import com.surprising.aeron.service.state.model.RiskLaneProgress;
import com.surprising.aeron.service.state.model.CoreRiskStatus;
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
import com.surprising.aeron.protocol.CoreMarginMode;
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
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Set;
import java.util.UUID;

public final class TradingCoreReducer {

    private final LaneTopology topology;

    public TradingCoreReducer() {
        this(LaneTopology.configured(Boolean.getBoolean("surprising.aeron.p10-characterization")));
    }

    TradingCoreReducer(LaneTopology topology) {
        if (topology == null) throw new IllegalArgumentException("lane topology is required");
        this.topology = topology;
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
        if (maxUsers <= 0 || maxUsers > 4096) {
            throw new IllegalArgumentException("invalid risk scan batch size");
        }
        CoreRiskScanControlView scanControl = state.riskState().scanControl();
        if (!scanControl.enabled()) return state;
        maxUsers = Math.min(maxUsers, scanControl.scanBatchSize());
        CoreRiskState.RiskScan scan = state.riskState().scans().values().stream()
                .filter(value -> !value.riskComplete()).min(java.util.Comparator
                        .comparingLong(CoreRiskState.RiskScan::lastScheduledRevision)
                        .thenComparing(CoreRiskState.RiskScan::symbol)).orElse(null);
        if (scan == null) {
            return state;
        }
        CoreInstrumentState instrument = state.instruments().get(scan.symbol());
        CoreMarkPriceState mark = state.riskState().markPrices().get(scan.symbol());
        if (instrument == null || mark == null || mark.priceSequence() != scan.priceSequence()) {
            throw new IllegalStateException("risk scan input is missing");
        }
        Map<String, CoreRiskSnapshot> snapshots = StateMapSupport.delta(state.riskState().snapshots());
        Map<Long, CoreLiquidationState> liquidations = StateMapSupport.delta(state.riskState().liquidations());
        long nextLiquidationId = state.riskState().nextLiquidationId();
        int laneCount = topology.accountLaneCount();
        if (!scan.laneProgress().isEmpty() && scan.laneProgress().size() != laneCount)
            throw new IllegalStateException("risk scan Lane topology differs");
        var laneScans = new CoreRiskState.RiskScan[laneCount];
        int[] allocations = new int[laneCount];
        int participants = 0;
        for (int lane = 0; lane < laneCount; lane++) {
            RiskLaneProgress cursor = scan.laneProgress().isEmpty()
                    ? (lane == scan.accountLaneId() ? riskLaneCursor(scan, scan.riskComplete())
                        : new RiskLaneProgress(0, lane < scan.accountLaneId(), 0, 0, "-", 0, 0, 0, 0, 0))
                    : scan.laneProgress().get(lane);
            if (!cursor.complete() && cursor.userId() == 0
                    && nextRiskUser(state, positionUserIndex, scan.symbol(), lane, cursor.lastUserId()) == null)
                cursor = new RiskLaneProgress(cursor.lastUserId(), true, 0, 0, "-", 0, 0, 0, 0, 0);
            laneScans[lane] = riskLaneInput(scan, lane, cursor);
            if (!cursor.complete()) participants++;
        }
        int selectedCount = Math.min(participants, maxUsers);
        int assigned = 0;
        int nextLane = scan.accountLaneId();
        for (int offset = 0; offset < laneCount && assigned < selectedCount; offset++) {
            int lane = (scan.accountLaneId() + offset) % laneCount;
            if (laneScans[lane].riskComplete()) continue;
            allocations[lane] = maxUsers / selectedCount + (assigned < maxUsers % selectedCount ? 1 : 0);
            nextLane = (lane + 1) % laneCount;
            assigned++;
        }
        // 状态形式的同步入口也按 Lane 编号提交；账户风险公式仍独立计算。
        for (int lane = 0; lane < laneCount; lane++) {
            if (allocations[lane] == 0) continue;
            CoreRiskState.RiskScan laneScan = laneScans[lane];
            int remainingWork = allocations[lane];
            while (remainingWork > 0) {
                CoreUserState user = laneScan.riskUserId() == 0
                        ? nextRiskUser(state, positionUserIndex, scan.symbol(), lane, laneScan.lastUserId())
                        : state.user(laneScan.riskUserId());
                if (user == null) break;
                RiskLaneProgress previous = scan.laneProgress().isEmpty() ? null : scan.laneProgress().get(lane);
                if (laneScan.riskUserId() == 0 || remainingWork == allocations[lane]
                        && (previous == null || previous.userRevision() != user.revision()
                            || previous.marketRevision() != state.riskState().marketRevision()))
                    laneScan = laneScan.withRiskProgress(false, user.userId(), 0, "-", 0,
                            0, 0, 0, 0, laneScan.lastUserId());
                RiskUserPage page = processRiskUserPage(state, laneScan, user, instrument, mark, remainingWork,
                        snapshots, liquidations, nextLiquidationId, liquidationIndex);
                laneScan = page.scan();
                nextLiquidationId = page.nextLiquidationId();
                remainingWork -= Math.max(1, page.workUnits());
                if (page.userComplete()) laneScan = laneScan.withRiskProgress(false, 0, 0, "-", 0,
                        0, 0, 0, 0, user.userId());
            }
            if (laneScan.riskUserId() == 0 && nextRiskUser(state, positionUserIndex, scan.symbol(), lane,
                    laneScan.lastUserId()) == null)
                laneScan = laneScan.withRiskProgress(true, 0, 0, "-", 0, 0, 0, 0, 0, laneScan.lastUserId());
            laneScans[lane] = laneScan;
        }
        var laneProgress = new java.util.ArrayList<RiskLaneProgress>(laneCount);
        boolean complete = true;
        for (int lane = 0; lane < laneCount; lane++) {
            var laneScan = laneScans[lane];
            var previous = scan.laneProgress().isEmpty() ? null : scan.laneProgress().get(lane);
            long userRevision = laneScan.riskUserId() == 0 ? 0 : allocations[lane] != 0
                    ? state.user(laneScan.riskUserId()).revision() : previous == null ? 0 : previous.userRevision();
            long marketRevision = allocations[lane] != 0 ? state.riskState().marketRevision()
                    : previous == null ? 0 : previous.marketRevision();
            laneProgress.add(riskLaneCursor(laneScan, laneScan.riskComplete(), userRevision, marketRevision));
            complete &= laneScan.riskComplete();
        }
        if (complete) nextLane = laneCount - 1;
        if (!complete) while (laneScans[nextLane].riskComplete()) nextLane = (nextLane + 1) % laneCount;
        CoreRiskState.RiskScan progress = laneScans[nextLane].withLaneProgress(laneProgress);
        Map<String, CoreRiskState.RiskScan> scans = StateMapSupport.delta(state.riskState().scans());
        CoreRiskState.RiskScan nextScan = progress.riskComplete()
                && scan.scanStartPriceSequence() != scan.priceSequence()
                ? new CoreRiskState.RiskScan(scan.symbol(), scan.priceSequence(), scan.priceSequence(), 0, false)
                        .withTriggerProgress(progress.triggerComplete(), progress.triggerPhase(),
                                progress.triggerPriceCursor(), progress.triggerOrderCursor(),
                                progress.triggerUpperId(), progress.triggerMarkPriceTicks(),
                                progress.triggerGeneratedAtEpochMillis())
                : progress;
        scans.put(scan.symbol(), nextScan.withLastScheduledRevision(Math.incrementExact(state.revision())));
        CoreRiskState nextRisk = new CoreRiskState(state.riskState().markPrices(), snapshots, liquidations,
                scans, nextLiquidationId, scanControl, state.riskState().marketRevision());
        return new TradingCoreState(state.productLine(), Math.incrementExact(state.revision()),
                state.users(), state.orders(),
                state.instruments(), nextRisk, state.treasuryState(),
                state.leverages(), state.algoOrders(), state.cancelAllAfterTimers(), state.clientOrderIndex(),
                state.triggerOrders());
    }

    private static RiskLaneProgress riskLaneCursor(CoreRiskState.RiskScan scan, boolean complete) {
        return riskLaneCursor(scan, complete, 0, 0);
    }

    private static RiskLaneProgress riskLaneCursor(CoreRiskState.RiskScan scan, boolean complete,
            long userRevision, long marketRevision) {
        return new RiskLaneProgress(scan.lastUserId(), complete, scan.riskUserId(), scan.riskPhase(),
                scan.riskPositionCursor(), scan.riskReservationCursor(), scan.riskUnrealizedPnlUnits(),
                scan.riskMaintenanceMarginUnits(), scan.riskIsolatedMarginUnits(), scan.riskIsolatedReservationUnits(),
                scan.riskUserId() == 0 ? 0 : userRevision, scan.riskUserId() == 0 ? 0 : marketRevision);
    }

    private static CoreRiskState.RiskScan riskLaneInput(CoreRiskState.RiskScan scan, int lane, RiskLaneProgress cursor) {
        return new CoreRiskState.RiskScan(scan.symbol(), lane, scan.priceSequence(), scan.scanStartPriceSequence(),
                cursor.lastUserId(), cursor.complete(), cursor.userId(), cursor.phase(), cursor.positionCursor(),
                cursor.reservationCursor(), cursor.unrealizedPnlUnits(), cursor.maintenanceMarginUnits(),
                cursor.isolatedMarginUnits(), cursor.isolatedReservationUnits(), scan.triggerComplete(),
                scan.triggerPhase(), scan.triggerPriceCursor(), scan.triggerOrderCursor(), scan.triggerUpperId(),
                scan.triggerMarkPriceTicks(), scan.triggerGeneratedAtEpochMillis(), scan.triggerOcoOrderId(),
                scan.triggerOcoCursor(), scan.lastScheduledRevision());
    }

    public TradingCoreState updateRiskScanControl(TradingCoreState state,
                                                  UpdateRiskScanControlCommand command,
                                                  long updatedAtEpochMillis) {
        return RiskScanControlStateTransitions.update(state, command, updatedAtEpochMillis);
    }

    private long updateIsolatedRisk(TradingCoreState state, CoreUserState user, CorePositionState position,
                                    CoreInstrumentState instrument, CoreMarkPriceState mark,
                                    Map<String, CoreRiskSnapshot> snapshots,
                                    Map<Long, CoreLiquidationState> liquidations, long nextLiquidationId,
                                    LiquidationIndex liquidationIndex) {
        PositionRisk risk = positionRisk(position, instrument, mark);
        long equity = Math.addExact(position.positionMarginUnits(), risk.equityDeltaUnits());
        long ratio = riskRatio(risk.maintenanceMarginUnits(), equity);
        CoreRiskStatus status = riskStatus(ratio);
        CoreRiskSnapshot snapshot = new CoreRiskSnapshot(user.userId(), position.symbol(), position.positionSide(),
                mark.priceSequence(), equity, risk.unrealizedPnlUnits(), risk.maintenanceMarginUnits(), ratio, status);
        snapshots.put(snapshot.key(), snapshot);
        return ensureLiquidation(user.userId(), position, instrument, mark.priceSequence(), status,
                liquidations, nextLiquidationId, liquidationIndex);
    }

    private RiskUserPage processRiskUserPage(TradingCoreState state, CoreRiskState.RiskScan scan,
                                             CoreUserState user, CoreInstrumentState changedInstrument,
                                             CoreMarkPriceState changedMark, int maxWork,
                                             Map<String, CoreRiskSnapshot> snapshots,
                                             Map<Long, CoreLiquidationState> liquidations,
                                             long nextLiquidationId, LiquidationIndex liquidationIndex) {
        int phase = scan.riskPhase();
        String positionCursor = scan.riskPositionCursor();
        long reservationCursor = scan.riskReservationCursor();
        long unrealized = scan.riskUnrealizedPnlUnits();
        long maintenance = scan.riskMaintenanceMarginUnits();
        long isolatedMargin = scan.riskIsolatedMarginUnits();
        long isolatedReservation = scan.riskIsolatedReservationUnits();
        int work = 0;
        while (work < maxWork) {
            if (phase == 0) {
                Map.Entry<String, CorePositionState> entry = nextEntry(user.positions(), positionCursor);
                if (entry == null) {
                    phase = 1;
                    positionCursor = "-";
                    continue;
                }
                positionCursor = entry.getKey();
                CorePositionState position = entry.getValue();
                work++;
                if (position.signedQuantitySteps() == 0) continue;
                if (position.marginMode() == CoreMarginMode.ISOLATED) {
                    if (position.marginAsset().equals(changedInstrument.settleAsset())) {
                        isolatedMargin = Math.addExact(isolatedMargin, position.positionMarginUnits());
                    }
                    if (position.symbol().equals(scan.symbol())) {
                        nextLiquidationId = updateIsolatedRisk(state, user, position, changedInstrument, changedMark,
                                snapshots, liquidations, nextLiquidationId, liquidationIndex);
                    }
                    continue;
                }
                if (!position.marginAsset().equals(changedInstrument.settleAsset())) continue;
                CoreInstrumentState positionInstrument = state.instruments().get(position.symbol());
                CoreMarkPriceState positionMark = state.riskState().markPrices().get(position.symbol());
                if (positionInstrument == null || positionMark == null) continue;
                PositionRisk risk = positionRisk(position, positionInstrument, positionMark);
                unrealized = Math.addExact(unrealized, risk.equityDeltaUnits());
                maintenance = Math.addExact(maintenance, risk.maintenanceMarginUnits());
                continue;
            }
            if (phase == 1) {
                Map.Entry<Long, OrderReservation> entry = nextEntry(user.reservations(), reservationCursor);
                if (entry == null) {
                    if (maintenance == 0) {
                        return new RiskUserPage(scan.withRiskProgress(false, user.userId(), 0, "-", 0,
                                unrealized, maintenance, isolatedMargin, isolatedReservation, scan.lastUserId()),
                                nextLiquidationId, work, true);
                    }
                    phase = 2;
                    positionCursor = "-";
                    continue;
                }
                reservationCursor = entry.getKey();
                OrderReservation reservation = entry.getValue();
                work++;
                CoreOrderState order = state.orders().get(reservation.orderId());
                if (order != null && order.marginMode() == CoreMarginMode.ISOLATED
                        && reservation.asset().equals(changedInstrument.settleAsset())) {
                    isolatedReservation = Math.addExact(isolatedReservation, reservation.remainingUnits());
                }
                continue;
            }
            Map.Entry<String, CorePositionState> entry = nextEntry(user.positions(), positionCursor);
            if (entry == null) {
                return new RiskUserPage(scan.withRiskProgress(false, user.userId(), 0, "-", 0,
                        unrealized, maintenance, isolatedMargin, isolatedReservation, scan.lastUserId()),
                        nextLiquidationId, work, true);
            }
            positionCursor = entry.getKey();
            CorePositionState position = entry.getValue();
            work++;
            if (position.signedQuantitySteps() == 0 || position.marginMode() != CoreMarginMode.CROSS
                    || !position.marginAsset().equals(changedInstrument.settleAsset())) continue;
            CoreInstrumentState positionInstrument = state.instruments().get(position.symbol());
            CoreMarkPriceState positionMark = state.riskState().markPrices().get(position.symbol());
            if (positionInstrument == null || positionMark == null) continue;
            PositionRisk risk = positionRisk(position, positionInstrument, positionMark);
            AssetBalance balance = user.balances().get(changedInstrument.settleAsset());
            long wallet = balance == null ? 0 : Math.subtractExact(
                    Math.subtractExact(balance.totalUnits(), isolatedMargin), isolatedReservation);
            if (wallet < 0) throw new IllegalStateException("isolated margin exceeds wallet balance");
            long equity = Math.addExact(wallet, unrealized);
            long ratio = riskRatio(maintenance, equity);
            CoreRiskStatus status = riskStatus(ratio);
            CoreRiskSnapshot snapshot = new CoreRiskSnapshot(user.userId(), position.symbol(),
                    position.positionSide(), positionMark.priceSequence(), equity, risk.unrealizedPnlUnits(),
                    risk.maintenanceMarginUnits(), ratio, status);
            snapshots.put(snapshot.key(), snapshot);
            nextLiquidationId = ensureLiquidation(user.userId(), position, positionInstrument,
                    positionMark.priceSequence(), status, liquidations, nextLiquidationId, liquidationIndex);
        }
        return new RiskUserPage(scan.withRiskProgress(false, user.userId(), phase, positionCursor,
                reservationCursor, unrealized, maintenance, isolatedMargin, isolatedReservation,
                scan.lastUserId()), nextLiquidationId, work, false);
    }

    private PositionRisk positionRisk(CorePositionState position, CoreInstrumentState instrument,
                                      CoreMarkPriceState mark) {
        long unrealized = PerpetualContractMath.unrealizedPnlUnits(instrument.contractType(),
                position.signedQuantitySteps(), position.entryPriceTicks(), mark.markPriceTicks(),
                instrument.notionalMultiplierUnits(), instrument.priceTickUnits(), instrument.settleScaleUnits());
        long maintenance = CoreContractMath.maintenanceMarginUnits(instrument,
                position.signedQuantitySteps(), mark.markPriceTicks(), mark.indexPriceTicks(),
                mark.forwardPriceTicks());
        long equityDelta = instrument.contractType().isOption()
                ? OptionContractMath.optionMarketValueUnits(instrument, position.signedQuantitySteps(),
                mark.markPriceTicks()) : unrealized;
        return new PositionRisk(position, instrument, mark, unrealized, maintenance, equityDelta);
    }

    private long ensureLiquidation(long userId, CorePositionState position, CoreInstrumentState instrument,
                                   long priceSequence, CoreRiskStatus status,
                                   Map<Long, CoreLiquidationState> liquidations, long nextLiquidationId,
                                   LiquidationIndex liquidationIndex) {
        long activeId = liquidationIndex == null ? 0
                : liquidationIndex.activeId(userId, position.symbol(), position.positionSide());
        CoreLiquidationState active = activeId == 0 ? null : liquidations.get(activeId);
        if (liquidationIndex == null) {
            active = liquidations.values().stream().filter(value -> value.userId() == userId
                    && value.symbol().equals(position.symbol()) && value.positionSide() == position.positionSide()
                    && value.status() != CoreLiquidationState.Status.COMPLETED
                    && value.status() != CoreLiquidationState.Status.CANCELED).findFirst().orElse(null);
        }
        if (status != CoreRiskStatus.LIQUIDATION
                || !CoreRiskPolicy.canLiquidate(instrument.contractType(), position.signedQuantitySteps())) {
            if (active != null && active.status() == CoreLiquidationState.Status.PLANNED) {
                liquidations.put(active.liquidationId(), active.canceled());
            }
            return nextLiquidationId;
        }
        if (active != null) {
            if (active.status() == CoreLiquidationState.Status.PLANNED) {
                liquidations.put(active.liquidationId(), active.refreshed(position.marginMode(), priceSequence,
                        position.signedQuantitySteps()));
            }
            return nextLiquidationId;
        }
        CoreLiquidationState liquidation = new CoreLiquidationState(nextLiquidationId, userId, position.symbol(),
                position.marginMode(), position.positionSide(), instrument.changeId(), priceSequence,
                position.signedQuantitySteps(), Math.absExact(position.signedQuantitySteps()), 0,
                0, 0, 0, CoreLiquidationState.Status.PLANNED);
        liquidations.put(nextLiquidationId, liquidation);
        return Math.incrementExact(nextLiquidationId);
    }

    private long riskRatio(long maintenance, long equity) {
        return maintenance <= 0 ? 0 : equity <= 0 ? Long.MAX_VALUE : safeRatio(maintenance, equity);
    }

    private CoreRiskStatus riskStatus(long ratio) {
        return CoreRiskPolicy.status(ratio);
    }

    private record PositionRisk(CorePositionState position, CoreInstrumentState instrument,
                                CoreMarkPriceState mark, long unrealizedPnlUnits,
                                long maintenanceMarginUnits, long equityDeltaUnits) {}

    private record RiskUserPage(CoreRiskState.RiskScan scan, long nextLiquidationId,
                                int workUnits, boolean userComplete) {}

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
        if (nextCursorOrderId <= 0) throw new IllegalArgumentException("liquidation cursor must advance");
        CoreLiquidationState liquidation = state.riskState().liquidations().get(command.liquidationId());
        if (liquidation == null) throw new CoreStateRejectedException("LIQUIDATION_NOT_FOUND",
                "liquidation plan does not exist");
        if (liquidation.status() == CoreLiquidationState.Status.ORDERED
                && liquidation.nextCancelOrderId() != command.cursorOrderId()) {
            throw new CoreStateRejectedException("LIQUIDATION_CURSOR_CONFLICT",
                    "liquidation cancellation cursor does not match state");
        }
        TradingCoreState canceled = cancelLifecycleOrders(state, orders);
        Map<Long, CoreLiquidationState> liquidations = StateMapSupport.delta(canceled.riskState().liquidations());
        liquidations.put(command.liquidationId(), liquidation.ordered(nextCursorOrderId));
        CoreRiskState risk = new CoreRiskState(canceled.riskState().markPrices(), canceled.riskState().snapshots(),
                liquidations, canceled.riskState().scans(), canceled.riskState().nextLiquidationId(),
                canceled.riskState().scanControl(), state.riskState().marketRevision());
        return new TradingCoreState(canceled.productLine(), Math.incrementExact(canceled.revision()), canceled.users(),
                canceled.orders(), canceled.instruments(), risk, canceled.treasuryState(),
                canceled.leverages(), canceled.algoOrders(), canceled.cancelAllAfterTimers(),
                canceled.clientOrderIndex(), canceled.triggerOrders());
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
        return executeLiquidation(state, command, true);
    }

    public TradingCoreState executeLiquidationAfterCancellation(TradingCoreState state,
                                                                ExecuteLiquidationCommand command) {
        return executeLiquidation(state, command, false);
    }

    private TradingCoreState executeLiquidation(TradingCoreState state, ExecuteLiquidationCommand command,
                                                boolean cancelOpenOrders) {
        CoreLiquidationState liquidation = state.riskState().liquidations().get(command.liquidationId());
        if (liquidation == null) {
            throw new CoreStateRejectedException("LIQUIDATION_NOT_FOUND", "liquidation plan does not exist");
        }
        if (liquidation.status() != CoreLiquidationState.Status.PLANNED
                && liquidation.status() != CoreLiquidationState.Status.ORDERED) {
            throw new CoreStateRejectedException("LIQUIDATION_STATE_CONFLICT", "liquidation is not planned");
        }
        validateLiquidationPrice(state, liquidation, command);
        if (!isLiquidationExecutable(state, liquidation)) {
            return cancelLiquidation(state, liquidation);
        }
        CoreInstrumentState instrument = requireInstrument(state, liquidation.symbol(),
                liquidation.instrumentChangeId());
        CoreUserState user = state.user(liquidation.userId());
        String positionKey = positionKey(liquidation.symbol(), liquidation.positionSide());
        CorePositionState position = user.positions().get(positionKey);
        TradingCoreState canceled = cancelOpenOrders
                ? OrderStateTransitions.cancelUserSymbolOrders(state, user.userId(), liquidation.symbol()) : state;
        user = canceled.user(user.userId());
        position = user.positions().get(positionKey);
        AssetBalance balance = requireBalance(user, instrument.settleAsset());
        long currentAbs = Math.absExact(position.signedQuantitySteps());
        long closeQuantity = liquidation.closeQuantitySteps();
        long remainingAbs = Math.subtractExact(currentAbs, closeQuantity);
        long releasedMargin = position.positionMarginUnits() == 0 ? 0
                : proportional(position.positionMarginUnits(), closeQuantity, currentAbs);
        long signedCloseQuantity = position.signedQuantitySteps() > 0
                ? closeQuantity : Math.negateExact(closeQuantity);
        long pnl = instrument.contractType().isOption()
                ? OptionContractMath.optionMarketValueUnits(instrument, signedCloseQuantity,
                command.executionPriceTicks())
                : CoreContractMath.pnlUnits(instrument, signedCloseQuantity,
                position.entryPriceTicks(), command.executionPriceTicks());
        long feeDue = Math.negateExact(CoreContractMath.feeDeltaUnits(instrument,
                command.executionPriceTicks(), liquidation.closeQuantitySteps(), command.liquidationFeeRatePpm()));
        LiquidationCashResult cash = applyLiquidationCash(balance, liquidation.marginMode(), releasedMargin, pnl,
                feeDue);
        long uncovered = pnl < 0 ? Math.subtractExact(Math.negateExact(pnl),
                Math.negateExact(Math.min(0, cash.appliedDelta()))) : 0;
        long collectedFee = cash.collectedFeeUnits();
        CoreTreasuryState treasury = canceled.treasuryState()
                .adjustInsurance(instrument.settleAsset(),
                        Math.addExact(Math.negateExact(cash.appliedDelta()), collectedFee))
                .adjustDeficit(instrument.settleAsset(), uncovered)
                .adjustClearingPnl(instrument.settleAsset(), uncovered);
        Map<String, AssetBalance> balances = StateMapSupport.delta(user.balances());
        balances.put(instrument.settleAsset(), cash.balance());
        Map<String, CorePositionState> positions = StateMapSupport.delta(user.positions());
        long nextQuantity = remainingAbs == 0 ? 0
                : position.signedQuantitySteps() > 0 ? remainingAbs : Math.negateExact(remainingAbs);
        long nextEntryValue = remainingAbs == 0 ? 0
                : proportional(position.entryValueTicks(), remainingAbs, currentAbs);
        positions.put(positionKey, new CorePositionState(instrument.symbol(), instrument.settleAsset(),
                position.marginMode(), position.positionSide(), remainingAbs == 0 ? 0 : position.instrumentChangeId(),
                nextQuantity, remainingAbs == 0 ? 0 : position.entryPriceTicks(), nextEntryValue,
                Math.addExact(position.realizedPnlUnits(), instrument.contractType().isOption() ? 0 : pnl),
                Math.subtractExact(position.positionMarginUnits(), releasedMargin)));
        CoreUserState nextUser = user.transition(Math.incrementExact(user.revision()),
                balances, user.reservations(), positions, user.positionMode());
        Map<Long, CoreUserState> users = StateMapSupport.delta(canceled.users());
        users.put(nextUser.userId(), nextUser);
        Map<Long, CoreLiquidationState> liquidations = StateMapSupport.delta(canceled.riskState().liquidations());
        liquidations.put(liquidation.liquidationId(), liquidation.executed(uncovered,
                command.executionPriceTicks(), command.liquidationFeeRatePpm(), collectedFee));
        CoreRiskState risk = new CoreRiskState(canceled.riskState().markPrices(), canceled.riskState().snapshots(),
                liquidations, canceled.riskState().scans(), canceled.riskState().nextLiquidationId(),
                canceled.riskState().scanControl(), state.riskState().marketRevision());
        return new TradingCoreState(canceled.productLine(), Math.incrementExact(canceled.revision()), users,
                canceled.orders(), canceled.instruments(), risk, treasury,
                canceled.leverages(), canceled.algoOrders(), canceled.cancelAllAfterTimers(), canceled.clientOrderIndex(),
                canceled.triggerOrders());
    }

    public boolean isLiquidationExecutable(TradingCoreState state, ExecuteLiquidationCommand command) {
        CoreLiquidationState liquidation = state.riskState().liquidations().get(command.liquidationId());
        if (liquidation == null || (liquidation.status() != CoreLiquidationState.Status.PLANNED
                && liquidation.status() != CoreLiquidationState.Status.ORDERED)) return false;
        validateLiquidationPrice(state, liquidation, command);
        return isLiquidationExecutable(state, liquidation);
    }

    private static boolean isLiquidationExecutable(TradingCoreState state, CoreLiquidationState liquidation) {
        CoreInstrumentState instrument = state.instruments().get(liquidation.symbol());
        if (instrument == null || !CoreRiskPolicy.canLiquidate(
                instrument.contractType(), liquidation.signedQuantitySteps())) return false;
        CoreUserState user = state.user(liquidation.userId());
        CorePositionState position = user == null ? null
                : user.positions().get(positionKey(liquidation.symbol(), liquidation.positionSide()));
        CoreRiskSnapshot risk = state.riskState().snapshots().get(
                riskKey(liquidation.userId(), liquidation.symbol(), liquidation.positionSide()));
        return position != null && position.instrumentChangeId() == liquidation.instrumentChangeId()
                && position.marginMode() == liquidation.marginMode()
                && position.signedQuantitySteps() == liquidation.signedQuantitySteps()
                && risk != null && risk.priceSequence() == liquidation.triggerPriceSequence()
                && risk.status() == CoreRiskStatus.LIQUIDATION;
    }

    private static void validateLiquidationPrice(TradingCoreState state, CoreLiquidationState liquidation,
                                                 ExecuteLiquidationCommand command) {
        CoreMarkPriceState mark = state.riskState().markPrices().get(liquidation.symbol());
        if (mark == null || mark.priceSequence() != liquidation.triggerPriceSequence()
                || command.triggerPriceSequence() > 0
                && command.triggerPriceSequence() != liquidation.triggerPriceSequence()
                || command.executionPriceTicks() != mark.markPriceTicks()) {
            throw new CoreStateRejectedException("STALE_MARK_PRICE", "liquidation mark price changed");
        }
    }

    private static TradingCoreState cancelLiquidation(TradingCoreState state, CoreLiquidationState liquidation) {
        Map<Long, CoreLiquidationState> liquidations = StateMapSupport.delta(state.riskState().liquidations());
        liquidations.put(liquidation.liquidationId(), liquidation.canceled());
        CoreRiskState risk = new CoreRiskState(state.riskState().markPrices(), state.riskState().snapshots(),
                liquidations, state.riskState().scans(), state.riskState().nextLiquidationId(),
                state.riskState().scanControl(), state.riskState().marketRevision());
        return new TradingCoreState(state.productLine(), Math.incrementExact(state.revision()),
                state.users(), state.orders(),
                state.instruments(), risk, state.treasuryState(),
                state.leverages(), state.algoOrders(), state.cancelAllAfterTimers(), state.clientOrderIndex(),
                state.triggerOrders());
    }

    private static String riskKey(long userId, String symbol, CorePositionSide positionSide) {
        return positionSide == CorePositionSide.NET
                ? userId + ":" + symbol : userId + ":" + symbol + ":" + positionSide.name();
    }

    public TradingCoreState resolveLiquidation(TradingCoreState state, ResolveLiquidationCommand command) {
        CoreLiquidationState liquidation = state.riskState().liquidations().get(command.liquidationId());
        if (liquidation == null) {
            throw new CoreStateRejectedException("LIQUIDATION_NOT_FOUND", "liquidation plan does not exist");
        }
        CoreInstrumentState instrument = requireInstrument(state, liquidation.symbol(),
                liquidation.instrumentChangeId());
        CoreLiquidationState.Status nextStatus;
        CoreTreasuryState treasury = state.treasuryState();
        switch (command.resolution()) {
            case INSURANCE -> {
                if (liquidation.status() != CoreLiquidationState.Status.INSURANCE_REQUIRED) {
                    throw new CoreStateRejectedException("LIQUIDATION_STATE_CONFLICT",
                            "insurance resolution requires insurance state");
                }
                if (command.coveredUnits() > liquidation.deficitUnits()) {
                    throw new CoreStateRejectedException("INSURANCE_COVER_EXCEEDS_DEFICIT",
                            "insurance coverage must be within liquidation deficit");
                }
                long available = treasury.insuranceBalances().getOrDefault(instrument.settleAsset(), 0L);
                if (!InsuranceAllocationPolicy.isNext(state, liquidation.liquidationId())) {
                    throw new CoreStateRejectedException("INSURANCE_RESOLUTION_ORDER_MISMATCH",
                            "insurance claims must resolve in deterministic priority order");
                }
                long expectedCoverage = InsuranceAllocationPolicy.expectedCoverage(state, liquidation.liquidationId());
                if (command.coveredUnits() != expectedCoverage) {
                    throw new CoreStateRejectedException("INSURANCE_ALLOCATION_MISMATCH",
                            "insurance coverage does not match deterministic allocation");
                }
                if (command.coveredUnits() > available) {
                    throw new CoreStateRejectedException("INSUFFICIENT_AVAILABLE_BALANCE",
                            "insurance fund balance is insufficient");
                }
                if (command.coveredUnits() != 0) {
                    treasury = treasury.adjustInsurance(instrument.settleAsset(),
                            Math.negateExact(command.coveredUnits())).adjustDeficit(
                            instrument.settleAsset(), Math.negateExact(command.coveredUnits()));
                }
                nextStatus = command.coveredUnits() == liquidation.deficitUnits()
                        ? CoreLiquidationState.Status.COMPLETED : CoreLiquidationState.Status.ADL_REQUIRED;
            }
            case ADL -> {
                throw new CoreStateRejectedException("INVALID_COMMAND",
                        "ADL resolution requires atomic target deleveraging");
            }
            case COMPLETED -> {
                if (command.coveredUnits() != 0) {
                    throw new CoreStateRejectedException("INVALID_COMMAND", "completed resolution covers no units");
                }
                if (liquidation.deficitUnits() != 0) {
                    throw new CoreStateRejectedException("LIQUIDATION_DEFICIT_REMAINS",
                            "liquidation deficit must be fully covered before completion");
                }
                nextStatus = CoreLiquidationState.Status.COMPLETED;
            }
            default -> throw new IllegalStateException("unknown liquidation resolution");
        }
        Map<Long, CoreLiquidationState> liquidations = StateMapSupport.delta(state.riskState().liquidations());
        CoreLiquidationState nextLiquidation = command.resolution() == ResolveLiquidationCommand.Resolution.COMPLETED
                ? liquidation.withStatus(nextStatus) : liquidation.covered(command.coveredUnits(), nextStatus);
        liquidations.put(liquidation.liquidationId(), nextLiquidation);
        CoreRiskState risk = new CoreRiskState(state.riskState().markPrices(), state.riskState().snapshots(),
                liquidations, state.riskState().scans(), state.riskState().nextLiquidationId(),
                state.riskState().scanControl(), state.riskState().marketRevision());
        return new TradingCoreState(state.productLine(), Math.incrementExact(state.revision()),
                state.users(), state.orders(), state.instruments(), risk, treasury,
                state.leverages(), state.algoOrders(), state.cancelAllAfterTimers(), state.clientOrderIndex(),
                state.triggerOrders());
    }

    public TradingCoreState adjustInsuranceFund(TradingCoreState state,
                                                com.surprising.aeron.protocol.AdjustInsuranceFundCommand command) {
        long current = state.treasuryState().insuranceBalances().getOrDefault(command.asset(), 0L);
        if (command.deltaUnits() < 0 && Math.negateExact(command.deltaUnits()) > current) {
            throw new CoreStateRejectedException("INSUFFICIENT_AVAILABLE_BALANCE",
                    "insurance fund balance is insufficient");
        }
        CoreTreasuryState treasury = state.treasuryState().adjustInsurance(command.asset(), command.deltaUnits());
        return new TradingCoreState(state.productLine(), Math.incrementExact(state.revision()),
                state.users(), state.orders(),
                state.instruments(), state.riskState(), treasury,
                state.leverages(), state.algoOrders(), state.cancelAllAfterTimers(), state.clientOrderIndex(),
                state.triggerOrders());
    }

    public TradingCoreState executeAdl(TradingCoreState state,
                                       com.surprising.aeron.protocol.ExecuteAdlCommand command) {
        CoreLiquidationState liquidation = state.riskState().liquidations().get(command.liquidationId());
        if (liquidation == null) {
            throw new CoreStateRejectedException("LIQUIDATION_NOT_FOUND", "liquidation plan does not exist");
        }
        if (liquidation.status() != CoreLiquidationState.Status.ADL_REQUIRED) {
            throw new CoreStateRejectedException("LIQUIDATION_STATE_CONFLICT", "ADL requires ADL state");
        }
        if (!liquidation.symbol().equals(command.symbol()) || command.targetUserId() == liquidation.userId()
                || command.coveredUnits() > liquidation.deficitUnits()) {
            throw new CoreStateRejectedException("INVALID_COMMAND", "ADL command does not match liquidation");
        }
        CoreInstrumentState instrument = requireInstrument(state, liquidation.symbol(),
                liquidation.instrumentChangeId());
        CoreMarkPriceState mark = state.riskState().markPrices().get(liquidation.symbol());
        if (mark == null || mark.priceSequence() != command.markPriceSequence()) {
            throw new CoreStateRejectedException("STALE_MARK_PRICE", "ADL mark price changed");
        }
        CoreUserState target = state.user(command.targetUserId());
        String positionKey = positionKey(command.symbol(), command.positionSide());
        CorePositionState position = target == null ? null : target.positions().get(positionKey);
        if (position == null || position.marginMode() != command.marginMode()
                || position.signedQuantitySteps() != command.expectedSignedQuantitySteps()
                || position.entryPriceTicks() != command.expectedEntryPriceTicks()
                || Long.signum(position.signedQuantitySteps()) == Long.signum(liquidation.signedQuantitySteps())) {
            throw new CoreStateRejectedException("ADL_POSITION_CONFLICT", "ADL target position changed");
        }
        long totalProfit = CoreContractMath.pnlUnits(instrument, position.signedQuantitySteps(),
                position.entryPriceTicks(), mark.markPriceTicks());
        long coverCapacity = totalProfit <= 0 ? 0 : proportional(totalProfit, command.closeQuantitySteps(),
                Math.absExact(position.signedQuantitySteps()));
        if (coverCapacity < command.coveredUnits()) {
            throw new CoreStateRejectedException("ADL_PROFIT_INSUFFICIENT", "ADL target profit is insufficient");
        }
        long currentAbs = Math.absExact(position.signedQuantitySteps());
        long remainingAbs = Math.subtractExact(currentAbs, command.closeQuantitySteps());
        long nextQuantity = remainingAbs == 0 ? 0
                : position.signedQuantitySteps() > 0 ? remainingAbs : Math.negateExact(remainingAbs);
        long releasedMargin = proportional(position.positionMarginUnits(), command.closeQuantitySteps(), currentAbs);
        AssetBalance balance = requireBalance(target, instrument.settleAsset());
        if (releasedMargin > 0) balance = balance.release(releasedMargin);
        long closeCashDelta = instrument.contractType().isOption()
                ? OptionContractMath.optionMarketValueUnits(instrument,
                position.signedQuantitySteps() > 0 ? command.closeQuantitySteps()
                        : Math.negateExact(command.closeQuantitySteps()), mark.markPriceTicks())
                : coverCapacity;
        long targetCashDelta = Math.subtractExact(closeCashDelta, command.coveredUnits());
        if (targetCashDelta != 0) balance = balance.adjustAvailable(targetCashDelta);
        CoreTreasuryState treasury = state.treasuryState()
                .adjustClearingPnl(instrument.settleAsset(), Math.negateExact(targetCashDelta))
                .adjustDeficit(instrument.settleAsset(), Math.negateExact(command.coveredUnits()))
                .adjustClearingPnl(instrument.settleAsset(), Math.negateExact(command.coveredUnits()));
        Map<String, AssetBalance> balances = StateMapSupport.delta(target.balances());
        balances.put(instrument.settleAsset(), balance);
        long nextEntryValue = remainingAbs == 0 ? 0
                : proportional(position.entryValueTicks(), remainingAbs, currentAbs);
        Map<String, CorePositionState> positions = StateMapSupport.delta(target.positions());
        positions.put(positionKey, new CorePositionState(position.symbol(), position.marginAsset(),
                position.marginMode(), position.positionSide(), remainingAbs == 0 ? 0 : position.instrumentChangeId(),
                nextQuantity, remainingAbs == 0 ? 0 : position.entryPriceTicks(), nextEntryValue,
                Math.addExact(position.realizedPnlUnits(),
                        instrument.contractType().isOption() ? 0 : coverCapacity),
                Math.subtractExact(position.positionMarginUnits(), releasedMargin)));
        CoreUserState nextTarget = target.transition(Math.incrementExact(target.revision()),
                balances, target.reservations(), positions,
                target.positionMode());
        Map<Long, CoreUserState> users = StateMapSupport.delta(state.users());
        users.put(nextTarget.userId(), nextTarget);
        CoreLiquidationState.Status nextStatus = command.coveredUnits() == liquidation.deficitUnits()
                ? CoreLiquidationState.Status.COMPLETED : CoreLiquidationState.Status.ADL_REQUIRED;
        Map<Long, CoreLiquidationState> liquidations = StateMapSupport.delta(state.riskState().liquidations());
        liquidations.put(liquidation.liquidationId(), liquidation.covered(command.coveredUnits(), nextStatus));
        CoreRiskState risk = new CoreRiskState(state.riskState().markPrices(), state.riskState().snapshots(),
                liquidations, state.riskState().scans(), state.riskState().nextLiquidationId(),
                state.riskState().scanControl(), state.riskState().marketRevision());
        return new TradingCoreState(state.productLine(), Math.incrementExact(state.revision()), users,
                state.orders(), state.instruments(), risk, treasury,
                state.leverages(), state.algoOrders(), state.cancelAllAfterTimers(), state.clientOrderIndex(),
                state.triggerOrders());
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

    private static long safeRatio(long maintenance, long equity) {
        try {
            return Math.multiplyExact(maintenance, 1_000_000L) / equity;
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

    @SuppressWarnings("unchecked")
    private CoreUserState nextRiskUser(TradingCoreState state, PositionUserIndex index,
                                       String symbol, int accountLaneId, long lastUserId) {
        if (index == null) {
            Map<Long, CoreUserState> users = state.users();
            if (users instanceof NavigableMap<?, ?> navigable) {
                Map.Entry<Long, CoreUserState> next =
                        ((NavigableMap<Long, CoreUserState>) navigable).higherEntry(lastUserId);
                while (next != null && topology.accountLaneId(next.getKey()) != accountLaneId) {
                    next = ((NavigableMap<Long, CoreUserState>) navigable).higherEntry(next.getKey());
                }
                return next == null ? null : next.getValue();
            }
            return users.values().stream().filter(user -> user.userId() > lastUserId)
                    .filter(user -> topology.accountLaneId(user.userId()) == accountLaneId)
                    .min(java.util.Comparator.comparingLong(CoreUserState::userId)).orElse(null);
        }
        Set<Long> indexedUsers = index.users(symbol);
        Long nextUserId;
        if (indexedUsers instanceof NavigableSet<?> navigable) {
            nextUserId = ((NavigableSet<Long>) navigable).higher(lastUserId);
            while (nextUserId != null && topology.accountLaneId(nextUserId) != accountLaneId) {
                nextUserId = ((NavigableSet<Long>) navigable).higher(nextUserId);
            }
        } else {
            nextUserId = indexedUsers.stream().filter(userId -> userId > lastUserId)
                    .filter(userId -> topology.accountLaneId(userId) == accountLaneId)
                    .min(Long::compareTo).orElse(null);
        }
        return nextUserId == null ? null : state.user(nextUserId);
    }

    @SuppressWarnings("unchecked")
    private static Map.Entry<String, CorePositionState> nextEntry(Map<String, CorePositionState> positions,
                                                                  String cursor) {
        NavigableMap<String, CorePositionState> sorted = positions instanceof NavigableMap<?, ?> navigable
                ? (NavigableMap<String, CorePositionState>) navigable : new java.util.TreeMap<>(positions);
        return "-".equals(cursor) ? sorted.firstEntry() : sorted.higherEntry(cursor);
    }

    @SuppressWarnings("unchecked")
    private static Map.Entry<Long, OrderReservation> nextEntry(Map<Long, OrderReservation> reservations,
                                                               long cursor) {
        NavigableMap<Long, OrderReservation> sorted = reservations instanceof NavigableMap<?, ?> navigable
                ? (NavigableMap<Long, OrderReservation>) navigable : new java.util.TreeMap<>(reservations);
        return cursor == 0 ? sorted.firstEntry() : sorted.higherEntry(cursor);
    }

    private static List<CorePositionState> positionsForSymbol(CoreUserState user, String symbol) {
        String normalized = OrderReservation.normalizeSymbol(symbol);
        return user.positions().values().stream()
                .filter(position -> position.symbol().equals(normalized) && position.signedQuantitySteps() != 0)
                .toList();
    }

}
