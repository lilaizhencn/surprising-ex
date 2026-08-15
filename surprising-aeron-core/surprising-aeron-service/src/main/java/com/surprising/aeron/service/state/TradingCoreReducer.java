package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CancelOrderCommand;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.protocol.UpsertInstrumentCommand;
import com.surprising.aeron.protocol.ApplyMarkPriceCommand;
import com.surprising.aeron.protocol.ApplyFundingCommand;
import com.surprising.aeron.protocol.ContinueRiskScanCommand;
import com.surprising.aeron.protocol.SettleInstrumentCommand;
import com.surprising.aeron.protocol.ExecuteLiquidationCommand;
import com.surprising.aeron.protocol.ResolveLiquidationCommand;
import com.surprising.aeron.protocol.AdjustPositionMarginCommand;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CorePositionMode;
import com.surprising.aeron.protocol.UpdatePositionModeCommand;
import com.surprising.aeron.protocol.UpdateLeverageCommand;
import com.surprising.aeron.protocol.CoreTriggerOrderStateView;
import com.surprising.aeron.protocol.CoreTriggerOrderStatus;
import com.surprising.aeron.service.matching.CoreMatch;
import com.surprising.instrument.api.math.PerpetualContractMath;
import com.surprising.aeron.service.state.TradingCoreState.ClientOrderKey;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.UUID;

public final class TradingCoreReducer {

    public TradingCoreState upsertTriggerOrder(TradingCoreState state, long userId,
                                               CoreTriggerOrderStateView view) {
        return upsertTriggerOrder(state, userId, view, null);
    }

    public TradingCoreState upsertTriggerOrder(TradingCoreState state, long userId,
                                               CoreTriggerOrderStateView view,
                                               TriggerOrderIndex triggerOrderIndex) {
        requireUserId(userId);
        if (view.userId() != userId || view.productLine() != state.productLine()) {
            throw new CoreStateRejectedException("TRIGGER_ORDER_OWNER_MISMATCH", "trigger order owner mismatch");
        }
        CoreInstrumentState instrument = state.instruments().get(OrderReservation.normalizeSymbol(view.symbol()));
        if (instrument == null) {
            throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "trigger order instrument does not exist");
        }
        if (state.treasuryState().lifecycleSettlements().containsKey(instrument.symbol())) {
            throw new CoreStateRejectedException("INSTRUMENT_SETTLED", "instrument is already settled");
        }
        if (state.triggerOrders().containsKey(view.triggerOrderId())) {
            throw new CoreStateRejectedException("DUPLICATE_TRIGGER_ORDER_ID", "trigger order already exists");
        }
        boolean duplicateClient = triggerOrderIndex != null
                ? triggerOrderIndex.containsClient(userId, view.clientTriggerOrderId())
                : !view.clientTriggerOrderId().isEmpty() && state.triggerOrders().values().stream().anyMatch(order ->
                order.userId() == userId && order.clientTriggerOrderId().equals(view.clientTriggerOrderId()));
        if (duplicateClient) {
            throw new CoreStateRejectedException("DUPLICATE_CLIENT_TRIGGER_ORDER_ID",
                    "client trigger order id already exists");
        }
        validateTriggerPlacement(state, view, triggerOrderIndex);
        Map<Long, CoreTriggerOrderState> triggers = StateMapSupport.delta(state.triggerOrders());
        CoreTriggerOrderState trigger = CoreTriggerOrderState.from(view);
        if (trigger.instrumentVersion() == 0) {
            trigger = trigger.withExecutionSnapshot(instrument.version(), instrument.makerFeeRatePpm(),
                    instrument.takerFeeRatePpm());
        } else if (trigger.instrumentVersion() != instrument.version()) {
            throw new CoreStateRejectedException("STALE_INSTRUMENT_VERSION",
                    "trigger order instrument version is stale");
        }
        triggers.put(view.triggerOrderId(), trigger);
        return withTriggers(state, triggers);
    }

    private static void validateTriggerPlacement(TradingCoreState state, CoreTriggerOrderStateView view,
                                                 TriggerOrderIndex triggerOrderIndex) {
        CoreUserState user = state.user(view.userId());
        if (user == null) {
            throw new CoreStateRejectedException("USER_NOT_FOUND", "user does not exist");
        }
        if (!state.productLine().isDerivative()) {
            return;
        }
        if (user.positionMode() == CorePositionMode.ONE_WAY && view.positionSide().hedgeSide()
                || user.positionMode() == CorePositionMode.HEDGE && !view.positionSide().hedgeSide()) {
            throw new CoreStateRejectedException("POSITION_MODE_MISMATCH",
                    "trigger position side does not match user position mode");
        }
        CorePositionState position = user.positions().get(positionKey(view.symbol(), view.positionSide()));
        if (position == null || position.signedQuantitySteps() == 0) {
            throw new CoreStateRejectedException("TRIGGER_POSITION_REQUIRED",
                    "trigger order requires an open position");
        }
        if (position.marginMode() != view.marginMode()) {
            throw new CoreStateRejectedException("POSITION_MARGIN_ADJUSTMENT_INVALID",
                    "trigger margin mode does not match position");
        }
        CoreOrderSide closeSide = position.signedQuantitySteps() > 0 ? CoreOrderSide.SELL : CoreOrderSide.BUY;
        if (view.side() != closeSide) {
            throw new CoreStateRejectedException("TRIGGER_SIDE_NOT_REDUCING",
                    "trigger order side must reduce the current position");
        }
        long openReduceOnly = userOrders(state, user).stream()
                .filter(order -> order.status() == CoreOrderStatus.OPEN)
                .filter(CoreOrderState::reduceOnly)
                .filter(order -> order.symbol().equals(view.symbol())
                        && order.marginMode() == view.marginMode()
                        && order.positionSide() == view.positionSide()
                        && order.side() == closeSide)
                .mapToLong(CoreOrderState::remainingQuantitySteps)
                .reduce(0L, Math::addExact);
        long triggerCapacity = 0;
        long sameOcoGroupMax = 0;
        Iterable<Long> triggerIds = triggerOrderIndex == null
                ? state.triggerOrders().keySet()
                : triggerOrderIndex.ids(view.userId(), view.symbol(), view.marginMode(), view.positionSide());
        for (Long triggerId : triggerIds) {
            CoreTriggerOrderState trigger = state.triggerOrders().get(triggerId);
            if (trigger == null) continue;
            if (!trigger.status().open() || trigger.userId() != view.userId()
                    || !trigger.symbol().equals(view.symbol()) || trigger.marginMode() != view.marginMode()
                    || trigger.positionSide() != view.positionSide() || trigger.side() != closeSide) {
                continue;
            }
            triggerCapacity = Math.addExact(triggerCapacity, trigger.quantitySteps());
            if (!view.ocoGroupId().isEmpty() && view.ocoGroupId().equals(trigger.ocoGroupId())) {
                sameOcoGroupMax = Math.max(sameOcoGroupMax, trigger.quantitySteps());
            }
        }
        long projectedTriggerCapacity = Math.addExact(
                Math.subtractExact(triggerCapacity, sameOcoGroupMax),
                Math.max(sameOcoGroupMax, view.quantitySteps()));
        long projectedClose = Math.addExact(openReduceOnly, projectedTriggerCapacity);
        if (projectedClose > Math.absExact(position.signedQuantitySteps())) {
            throw new CoreStateRejectedException("TRIGGER_CLOSE_CAPACITY_EXCEEDED",
                    "trigger order quantity exceeds available position");
        }
    }

    public TradingCoreState cancelTriggerOrder(TradingCoreState state, long userId, long triggerOrderId) {
        requireUserId(userId);
        CoreTriggerOrderState current = state.triggerOrders().get(triggerOrderId);
        if (current == null) throw new CoreStateRejectedException("TRIGGER_ORDER_NOT_FOUND", "trigger order not found");
        if (current.userId() != userId) {
            throw new CoreStateRejectedException("TRIGGER_ORDER_OWNER_MISMATCH", "trigger order owner mismatch");
        }
        if (!current.status().open()) return state;
        return updateTrigger(state, current, CoreTriggerOrderStatus.CANCELED, 0, current.triggerSequence(),
                current.triggeredPriceTicks(), current.rejectReason(), current.updatedAtEpochMillis());
    }

    public TradingCoreState claimTriggerOrder(TradingCoreState state, long triggerOrderId, long triggerSequence,
                                              long triggeredPriceTicks, long triggeredAtEpochMillis) {
        CoreTriggerOrderState current = state.triggerOrders().get(triggerOrderId);
        if (current == null) throw new CoreStateRejectedException("TRIGGER_ORDER_NOT_FOUND", "trigger order not found");
        if (current.status() != CoreTriggerOrderStatus.PENDING) return state;
        return updateTrigger(state, current, CoreTriggerOrderStatus.TRIGGERING, 0, triggerSequence,
                triggeredPriceTicks, current.rejectReason(), triggeredAtEpochMillis);
    }

    public TradingCoreState completeTriggerOrder(TradingCoreState state, long triggerOrderId, boolean success,
                                                 long placedOrderId, String rejectReason, long completedAtEpochMillis) {
        CoreTriggerOrderState current = state.triggerOrders().get(triggerOrderId);
        if (current == null) throw new CoreStateRejectedException("TRIGGER_ORDER_NOT_FOUND", "trigger order not found");
        if (current.status() != CoreTriggerOrderStatus.TRIGGERING) return state;
        return updateTrigger(state, current, success ? CoreTriggerOrderStatus.TRIGGERED
                        : CoreTriggerOrderStatus.TRIGGER_FAILED, placedOrderId, current.triggerSequence(),
                current.triggeredPriceTicks(), rejectReason, completedAtEpochMillis);
    }

    public TradingCoreState updateTriggerTrailing(TradingCoreState state, long triggerOrderId,
                                                  long highestPriceTicks, long lowestPriceTicks,
                                                  long activatedAtEpochMillis) {
        CoreTriggerOrderState current = state.triggerOrders().get(triggerOrderId);
        if (current == null) throw new CoreStateRejectedException("TRIGGER_ORDER_NOT_FOUND", "trigger order not found");
        if (!current.status().open() || current.triggerType() != com.surprising.aeron.protocol.CoreTriggerOrderType.TRAILING_STOP) {
            return state;
        }
        Map<Long, CoreTriggerOrderState> triggers = StateMapSupport.delta(state.triggerOrders());
        triggers.put(triggerOrderId, new CoreTriggerOrderState(current.triggerOrderId(), current.productLine(), current.userId(),
                current.clientTriggerOrderId(), current.ocoGroupId(), current.symbol(), current.side(), current.triggerType(),
                current.triggerCondition(), current.triggerPriceTicks(), current.activationPriceTicks(), current.callbackRatePpm(),
                highestPriceTicks, lowestPriceTicks, activatedAtEpochMillis, current.orderType(), current.timeInForce(),
                current.priceTicks(), current.quantitySteps(), current.marginMode(), current.positionSide(), current.status(),
                current.placedOrderId(), current.triggerSequence(), current.triggeredPriceTicks(), current.rejectReason(),
                current.traceId(), current.expiresAtEpochMillis(), current.triggeredAtEpochMillis(), current.createdAtEpochMillis(),
                Math.max(current.updatedAtEpochMillis(), activatedAtEpochMillis), Math.incrementExact(current.revision()),
                current.instrumentVersion(), current.makerFeeRatePpm(), current.takerFeeRatePpm()));
        return withTriggers(state, triggers);
    }

    public TradingCoreState expireTriggerOrder(TradingCoreState state, long triggerOrderId,
                                               long expiredAtEpochMillis) {
        CoreTriggerOrderState current = state.triggerOrders().get(triggerOrderId);
        if (current == null) {
            throw new CoreStateRejectedException("TRIGGER_ORDER_NOT_FOUND", "trigger order not found");
        }
        if (current.status() != CoreTriggerOrderStatus.PENDING
                || current.expiresAtEpochMillis() == 0
                || current.expiresAtEpochMillis() > expiredAtEpochMillis) {
            return state;
        }
        return updateTrigger(state, current, CoreTriggerOrderStatus.EXPIRED, 0, current.triggerSequence(),
                current.triggeredPriceTicks(), current.rejectReason(), expiredAtEpochMillis);
    }

    public TradingCoreState retryTriggerOrder(TradingCoreState state, long triggerOrderId,
                                              long staleBeforeEpochMillis, long retryAtEpochMillis) {
        CoreTriggerOrderState current = state.triggerOrders().get(triggerOrderId);
        if (current == null) {
            throw new CoreStateRejectedException("TRIGGER_ORDER_NOT_FOUND", "trigger order not found");
        }
        if (current.status() != CoreTriggerOrderStatus.TRIGGERING
                || current.updatedAtEpochMillis() > staleBeforeEpochMillis) {
            return state;
        }
        return updateTrigger(state, current, CoreTriggerOrderStatus.PENDING, 0, 0, 0,
                current.rejectReason(), retryAtEpochMillis);
    }

    private TradingCoreState updateTrigger(TradingCoreState state, CoreTriggerOrderState current,
                                            CoreTriggerOrderStatus status, long placedOrderId, long triggerSequence,
                                            long triggeredPriceTicks, String rejectReason, long updatedAt) {
        Map<Long, CoreTriggerOrderState> triggers = StateMapSupport.delta(state.triggerOrders());
        triggers.put(current.triggerOrderId(), new CoreTriggerOrderState(current.triggerOrderId(), current.productLine(),
                current.userId(), current.clientTriggerOrderId(), current.ocoGroupId(), current.symbol(), current.side(),
                current.triggerType(), current.triggerCondition(), current.triggerPriceTicks(), current.activationPriceTicks(),
                current.callbackRatePpm(), current.highestPriceTicks(), current.lowestPriceTicks(), current.activatedAtEpochMillis(),
                current.orderType(), current.timeInForce(), current.priceTicks(), current.quantitySteps(), current.marginMode(),
                current.positionSide(), status, placedOrderId, triggerSequence, triggeredPriceTicks, rejectReason,
                current.traceId(), current.expiresAtEpochMillis(), status == CoreTriggerOrderStatus.TRIGGERED
                        || status == CoreTriggerOrderStatus.TRIGGER_FAILED ? updatedAt : current.triggeredAtEpochMillis(),
                current.createdAtEpochMillis(), updatedAt, Math.incrementExact(current.revision()),
                current.instrumentVersion(), current.makerFeeRatePpm(), current.takerFeeRatePpm()));
        return withTriggers(state, triggers);
    }

    private TradingCoreState withTriggers(TradingCoreState state, Map<Long, CoreTriggerOrderState> triggers) {
        return new TradingCoreState(state.productLine(), Math.incrementExact(state.revision()),
                StateMapSupport.delta(state.users()), StateMapSupport.delta(state.orders()),
                state.bookState(), state.instruments(), state.riskState(), state.treasuryState(),
                StateMapSupport.delta(state.leverages()), StateMapSupport.delta(state.algoOrders()),
                StateMapSupport.delta(state.cancelAllAfterTimers()), StateMapSupport.delta(state.clientOrderIndex()), triggers);
    }

    public TradingCoreState updateCancelAllAfter(
            TradingCoreState state,
            long userId,
            com.surprising.aeron.protocol.CoreCancelAllAfterCommand command) {
        requireUserId(userId);
        if (command.userId() != userId) {
            throw new CoreStateRejectedException("CANCEL_ALL_AFTER_OWNER_MISMATCH",
                    "cancel-all-after timer belongs to another user");
        }
        CoreCancelAllAfterKey key = new CoreCancelAllAfterKey(userId, command.symbolScope());
        CoreCancelAllAfterState current = state.cancelAllAfterTimers().get(key);
        CoreCancelAllAfterState next;
        switch (command.action()) {
            case SET -> {
                com.surprising.aeron.protocol.CoreCancelAllAfterStatus status = command.countdownMillis() == 0
                        ? com.surprising.aeron.protocol.CoreCancelAllAfterStatus.DISABLED
                        : com.surprising.aeron.protocol.CoreCancelAllAfterStatus.ACTIVE;
                if (status == com.surprising.aeron.protocol.CoreCancelAllAfterStatus.ACTIVE
                        && command.triggerAtEpochMillis() <= command.updatedAtEpochMillis()) {
                    throw new CoreStateRejectedException("INVALID_CANCEL_ALL_AFTER_TRIGGER",
                            "active cancel-all-after timer must trigger in the future");
                }
                next = new CoreCancelAllAfterState(userId, command.symbolScope(), command.countdownMillis(), status,
                        status == com.surprising.aeron.protocol.CoreCancelAllAfterStatus.ACTIVE
                                ? command.triggerAtEpochMillis() : 0,
                        command.updatedAtEpochMillis(), 0, 0, current == null ? 1 : Math.incrementExact(current.revision()));
            }
            case CLAIM -> {
                requireTimerRevision(current, command);
                if (current.status() != com.surprising.aeron.protocol.CoreCancelAllAfterStatus.ACTIVE
                        || current.triggerAtEpochMillis() > command.updatedAtEpochMillis()) {
                    throw new CoreStateRejectedException("CANCEL_ALL_AFTER_NOT_DUE", "timer is not due");
                }
                next = new CoreCancelAllAfterState(userId, current.symbolScope(), current.countdownMillis(),
                        com.surprising.aeron.protocol.CoreCancelAllAfterStatus.TRIGGERING,
                        current.triggerAtEpochMillis(), command.updatedAtEpochMillis(), current.canceledOrders(),
                        current.canceledTriggerOrders(), Math.incrementExact(current.revision()));
            }
            case COMPLETE -> {
                requireTimerRevision(current, command);
                if (current.status() != com.surprising.aeron.protocol.CoreCancelAllAfterStatus.TRIGGERING) {
                    throw new CoreStateRejectedException("CANCEL_ALL_AFTER_NOT_CLAIMED", "timer is not claimed");
                }
                next = new CoreCancelAllAfterState(userId, current.symbolScope(), current.countdownMillis(),
                        com.surprising.aeron.protocol.CoreCancelAllAfterStatus.TRIGGERED,
                        current.triggerAtEpochMillis(), command.updatedAtEpochMillis(), command.canceledOrders(),
                        command.canceledTriggerOrders(), Math.incrementExact(current.revision()));
            }
            case RETRY -> {
                requireTimerRevision(current, command);
                if (current.status() != com.surprising.aeron.protocol.CoreCancelAllAfterStatus.TRIGGERING) {
                    throw new CoreStateRejectedException("CANCEL_ALL_AFTER_NOT_CLAIMED", "timer is not claimed");
                }
                next = new CoreCancelAllAfterState(userId, current.symbolScope(), current.countdownMillis(),
                        com.surprising.aeron.protocol.CoreCancelAllAfterStatus.ACTIVE,
                        current.triggerAtEpochMillis(), command.updatedAtEpochMillis(), current.canceledOrders(),
                        current.canceledTriggerOrders(), Math.incrementExact(current.revision()));
            }
            default -> throw new CoreStateRejectedException("INVALID_CANCEL_ALL_AFTER_ACTION", "unsupported action");
        }
        Map<CoreCancelAllAfterKey, CoreCancelAllAfterState> timers = StateMapSupport.delta(state.cancelAllAfterTimers());
        timers.put(key, next);
        return new TradingCoreState(state.productLine(), Math.incrementExact(state.revision()),
                StateMapSupport.delta(state.users()), StateMapSupport.delta(state.orders()),
                state.bookState(), state.instruments(), state.riskState(), state.treasuryState(),
                StateMapSupport.delta(state.leverages()), StateMapSupport.delta(state.algoOrders()), timers,
                StateMapSupport.delta(state.clientOrderIndex()), StateMapSupport.delta(state.triggerOrders()));
    }

    private static void requireTimerRevision(
            CoreCancelAllAfterState current,
            com.surprising.aeron.protocol.CoreCancelAllAfterCommand command) {
        if (current == null) {
            throw new CoreStateRejectedException("CANCEL_ALL_AFTER_NOT_FOUND", "timer not found");
        }
        if (command.expectedRevision() != current.revision()) {
            throw new CoreStateRejectedException("STALE_CANCEL_ALL_AFTER_REVISION", "timer revision is stale");
        }
    }

    public TradingCoreState upsertAlgoOrder(TradingCoreState state, long userId,
                                             com.surprising.aeron.protocol.CoreAlgoOrderView view) {
        return upsertAlgoOrder(state, userId, view, null);
    }

    public TradingCoreState upsertAlgoOrder(TradingCoreState state, long userId,
                                             com.surprising.aeron.protocol.CoreAlgoOrderView view,
                                             AlgoOrderIndex algoOrderIndex) {
        requireUserId(userId);
        if (view.userId() != userId) {
            throw new CoreStateRejectedException("ALGO_ORDER_OWNER_MISMATCH", "algo order belongs to another user");
        }
        CoreAlgoOrderState next = CoreAlgoOrderState.from(view);
        CoreAlgoOrderState current = state.algoOrders().get(next.algoOrderId());
        if (current == null) {
            boolean duplicateClient = algoOrderIndex != null
                    ? algoOrderIndex.containsClient(userId, next.clientAlgoOrderId())
                    : !next.clientAlgoOrderId().isEmpty() && state.algoOrders().values().stream()
                    .anyMatch(value -> value.userId() == userId
                            && value.clientAlgoOrderId().equals(next.clientAlgoOrderId()));
            if (duplicateClient) throw new CoreStateRejectedException("DUPLICATE_CLIENT_ALGO_ORDER_ID",
                    "clientAlgoOrderId already exists");
            if (!next.childOrderIds().isEmpty() || next.revision() != 1) {
                throw new CoreStateRejectedException("INVALID_ALGO_ORDER_CREATE", "new algo order must start empty");
            }
        } else {
            requireSameAlgoIntent(current, next);
            if (next.revision() <= current.revision()) {
                throw new CoreStateRejectedException("STALE_ALGO_ORDER_REVISION",
                        "algo order revision is stale");
            }
            if (next.revision() != Math.incrementExact(current.revision())
                    || next.childOrderIds().size() < current.childOrderIds().size()
                    || !next.childOrderIds().subList(0, current.childOrderIds().size()).equals(current.childOrderIds())
                    || next.childOrderIds().size() > current.childOrderIds().size() + 1) {
                throw new CoreStateRejectedException("INVALID_ALGO_ORDER_REVISION", "algo order revision is not monotonic");
            }
            if (next.childOrderIds().size() > current.childOrderIds().size()) {
                long childOrderId = next.childOrderIds().getLast();
                CoreOrderState child = state.order(childOrderId);
                if (child == null || child.userId() != userId || !child.symbol().equals(next.symbol())) {
                    throw new CoreStateRejectedException("INVALID_ALGO_CHILD", "algo child order is not authoritative");
                }
            }
        }
        Map<Long, CoreAlgoOrderState> values = StateMapSupport.delta(state.algoOrders());
        values.put(next.algoOrderId(), next);
        return new TradingCoreState(state.productLine(), Math.incrementExact(state.revision()),
                StateMapSupport.delta(state.users()), StateMapSupport.delta(state.orders()),
                state.bookState(), state.instruments(), state.riskState(), state.treasuryState(),
                StateMapSupport.delta(state.leverages()), values, StateMapSupport.delta(state.cancelAllAfterTimers()),
                StateMapSupport.delta(state.clientOrderIndex()), StateMapSupport.delta(state.triggerOrders()));
    }

    private static void requireSameAlgoIntent(CoreAlgoOrderState left, CoreAlgoOrderState right) {
        if (left.userId() != right.userId() || !left.clientAlgoOrderId().equals(right.clientAlgoOrderId())
                || !left.symbol().equals(right.symbol()) || left.algoTypeCode() != right.algoTypeCode()
                || left.side() != right.side() || left.priceTicks() != right.priceTicks()
                || left.quantitySteps() != right.quantitySteps() || left.childQuantitySteps() != right.childQuantitySteps()
                || left.intervalSeconds() != right.intervalSeconds() || left.durationSeconds() != right.durationSeconds()
                || left.marginMode() != right.marginMode() || left.positionSide() != right.positionSide()
                || left.reduceOnly() != right.reduceOnly() || left.postOnly() != right.postOnly()
                || left.timeInForce() != right.timeInForce() || left.startAtEpochMillis() != right.startAtEpochMillis()
                || left.createdAtEpochMillis() != right.createdAtEpochMillis()) {
            throw new CoreStateRejectedException("ALGO_ORDER_INTENT_MISMATCH", "algo order intent is immutable");
        }
    }

    public java.util.List<com.surprising.aeron.protocol.CoreRiskSnapshotView> riskSnapshots(
            TradingCoreState state, long userId) {
        return riskSnapshots(state, userId, state.riskState().snapshots().keySet());
    }

    public java.util.List<com.surprising.aeron.protocol.CoreRiskSnapshotView> riskSnapshots(
            TradingCoreState state, long userId, java.util.Set<String> snapshotKeys) {
        return snapshotKeys.stream()
                .map(state.riskState().snapshots()::get)
                .filter(java.util.Objects::nonNull)
                .filter(risk -> userId == 0 || risk.userId() == userId)
                .filter(risk -> {
                    CoreUserState user = state.user(risk.userId());
                    CorePositionState position = user.positions()
                            .get(positionKey(risk.symbol(), risk.positionSide()));
                    return position != null && position.signedQuantitySteps() != 0;
                })
                .map(risk -> {
                    CoreUserState user = state.user(risk.userId());
                    CorePositionState position = user.positions().get(positionKey(risk.symbol(), risk.positionSide()));
                    CoreInstrumentState instrument = state.instruments().get(risk.symbol());
                    CoreMarkPriceState mark = state.riskState().markPrices().get(risk.symbol());
                    if (position == null || instrument == null || mark == null) {
                        throw new IllegalStateException("risk snapshot source state is missing");
                    }
                    long notional = com.surprising.instrument.api.math.PerpetualContractMath.notionalUnits(
                            instrument.contractType(), position.signedQuantitySteps(), mark.markPriceTicks(),
                            instrument.notionalMultiplierUnits(), instrument.priceTickUnits(),
                            instrument.settleScaleUnits());
                    long walletBalance = crossWalletBalance(state, user, instrument.settleAsset());
                    return new com.surprising.aeron.protocol.CoreRiskSnapshotView(risk.userId(), risk.symbol(),
                            position.marginMode(), risk.positionSide(), position.instrumentVersion(),
                            instrument.settleAsset(), position.signedQuantitySteps(), position.entryPriceTicks(),
                            mark.markPriceTicks(), notional, position.positionMarginUnits(), risk.priceSequence(), walletBalance,
                            risk.equityUnits(), risk.unrealizedPnlUnits(), risk.maintenanceMarginUnits(),
                            risk.marginRatioPpm(), risk.status().name());
                }).toList();
    }

    private static final long PPM = 1_000_000L;

    public TradingCoreState updateLeverage(TradingCoreState state, long userId, UpdateLeverageCommand command) {
        requireUserId(userId);
        if (!state.productLine().isDerivative()) {
            throw new CoreStateRejectedException("PRODUCT_LINE_UNSUPPORTED",
                    "leverage requires derivative product line");
        }
        CoreInstrumentState instrument = state.instruments().get(OrderReservation.normalizeSymbol(command.symbol()));
        if (instrument == null) {
            throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument does not exist");
        }
        long requestedRate = initialMarginRateFromLeverage(command.leveragePpm());
        long minimumRate = Math.max(instrument.initialMarginRatePpm(),
                CoreContractMath.riskBracket(instrument, 0).initialMarginRatePpm());
        if (requestedRate < minimumRate) {
            throw new CoreStateRejectedException("LEVERAGE_EXCEEDS_INSTRUMENT_LIMIT",
                    "leverage exceeds instrument maximum");
        }
        CoreUserState user = state.users().getOrDefault(userId, CoreUserState.empty(state.productLine(), userId));
        boolean openState = userOrders(state, user).stream().anyMatch(order ->
                        order.symbol().equals(instrument.symbol()) && order.marginMode() == command.marginMode()
                                && order.status() == CoreOrderStatus.OPEN)
                || user.positions().values().stream().anyMatch(position -> position.symbol().equals(instrument.symbol())
                        && position.marginMode() == command.marginMode() && position.signedQuantitySteps() != 0);
        CoreLeverageKey key = new CoreLeverageKey(userId, instrument.symbol(), command.marginMode());
        Long current = state.leverages().get(key);
        if (openState && (current == null || current.longValue() != command.leveragePpm())) {
            throw new CoreStateRejectedException("LEVERAGE_UPDATE_BLOCKED", "open orders or positions exist");
        }
        if (current != null && current.longValue() == command.leveragePpm()) return state;
        Map<CoreLeverageKey, Long> leverages = StateMapSupport.delta(state.leverages());
        leverages.put(key, command.leveragePpm());
        return new TradingCoreState(state.productLine(), Math.incrementExact(state.revision()),
                StateMapSupport.delta(state.users()), StateMapSupport.delta(state.orders()),
                state.bookState(), state.instruments(), state.riskState(), state.treasuryState(),
                leverages, StateMapSupport.delta(state.algoOrders()), StateMapSupport.delta(state.cancelAllAfterTimers()),
                StateMapSupport.delta(state.clientOrderIndex()), StateMapSupport.delta(state.triggerOrders()));
    }

    public TradingCoreState updatePositionMode(
            TradingCoreState state, long userId, UpdatePositionModeCommand command) {
        requireUserId(userId);
        if (!state.productLine().isDerivative()) {
            throw new CoreStateRejectedException("PRODUCT_LINE_UNSUPPORTED",
                    "position mode requires derivative product line");
        }
        CoreUserState user = state.users().getOrDefault(userId, CoreUserState.empty(state.productLine(), userId));
        if (user.positionMode() == command.positionMode()) {
            return state;
        }
        boolean openPosition = user.positions().values().stream()
                .anyMatch(position -> position.signedQuantitySteps() != 0);
        boolean openOrder = userOrders(state, user).stream()
                .anyMatch(order -> order.status() == CoreOrderStatus.OPEN);
        if (openPosition || openOrder || user.reservations().values().stream()
                .anyMatch(reservation -> reservation.remainingUnits() != 0)) {
            throw new CoreStateRejectedException("POSITION_MODE_SWITCH_BLOCKED",
                    "open positions or orders block position mode update");
        }
        CoreUserState nextUser = new CoreUserState(user.productLine(), user.userId(),
                Math.incrementExact(user.revision()), user.balances(), user.reservations(), user.positions(),
                command.positionMode());
        return replaceUser(state, nextUser, state.orders(), state.bookState());
    }

    public TradingCoreState adjustPositionMargin(
            TradingCoreState state, long userId, AdjustPositionMarginCommand command) {
        requireUserId(userId);
        if (command.marginMode() != CoreMarginMode.ISOLATED || command.amountUnits() == 0) {
            throw new CoreStateRejectedException("POSITION_MARGIN_ADJUSTMENT_INVALID",
                    "only isolated position margin can be adjusted");
        }
        CoreUserState user = state.user(userId);
        if (user == null) {
            throw new CoreStateRejectedException("POSITION_NOT_FOUND", "position does not exist");
        }
        String symbol = OrderReservation.normalizeSymbol(command.symbol());
        String key = command.positionSide().hedgeSide() ? symbol + ':' + command.positionSide().name() : symbol;
        CorePositionState position = user.positions().get(key);
        if (position == null || position.signedQuantitySteps() == 0
                || position.marginMode() != command.marginMode()
                || position.positionSide() != command.positionSide()) {
            throw new CoreStateRejectedException("POSITION_NOT_FOUND", "isolated position does not exist");
        }
        long units = Math.absExact(command.amountUnits());
        AssetBalance balance = requireBalance(user, position.marginAsset());
        long nextMargin;
        AssetBalance nextBalance;
        if (command.amountUnits() > 0) {
            nextBalance = balance.reserve(units);
            nextMargin = Math.addExact(position.positionMarginUnits(), units);
        } else {
            if (position.positionMarginUnits() < units) {
                throw new CoreStateRejectedException("POSITION_MARGIN_INSUFFICIENT",
                        "position margin is insufficient");
            }
            nextBalance = balance.release(units);
            nextMargin = Math.subtractExact(position.positionMarginUnits(), units);
        }
        Map<String, AssetBalance> balances = StateMapSupport.delta(user.balances());
        balances.put(nextBalance.asset(), nextBalance);
        Map<String, CorePositionState> positions = StateMapSupport.delta(user.positions());
        positions.put(key, new CorePositionState(position.symbol(), position.marginAsset(), position.marginMode(),
                position.positionSide(), position.instrumentVersion(), position.signedQuantitySteps(),
                position.entryPriceTicks(), position.entryValueTicks(), position.realizedPnlUnits(), nextMargin));
        CoreUserState nextUser = new CoreUserState(user.productLine(), user.userId(),
                Math.incrementExact(user.revision()), balances, user.reservations(), positions, user.positionMode());
        return replaceUser(state, nextUser, state.orders(), state.bookState());
    }

    public TradingCoreState adjustBalance(
            TradingCoreState state,
            long userId,
            BalanceAdjustmentCommand command) {
        requireUserId(userId);
        CoreUserState currentUser = state.users().getOrDefault(userId,
                CoreUserState.empty(state.productLine(), userId));
        String asset = AssetBalance.normalizeAsset(command.asset());
        AssetBalance currentBalance = currentUser.balances().getOrDefault(asset, new AssetBalance(asset, 0, 0));
        AssetBalance nextBalance = currentBalance.adjustAvailable(command.deltaUnits());

        Map<String, AssetBalance> balances = StateMapSupport.delta(currentUser.balances());
        balances.put(asset, nextBalance);
        CoreUserState nextUser = new CoreUserState(state.productLine(), userId,
                Math.incrementExact(currentUser.revision()), balances,
                currentUser.reservations(), currentUser.positions(), currentUser.positionMode());
        return replaceUser(state, nextUser, state.orders(), state.bookState());
    }

    public TradingCoreState placeOrder(TradingCoreState state, long userId, PlaceOrderCommand command) {
        return placeOrder(state, userId, command, new UUID(0, command.orderId()));
    }

    public TradingCoreState placeOrder(
            TradingCoreState state,
            long userId,
            PlaceOrderCommand command,
            UUID commandId) {
        return placeOrder(state, userId, command, commandId, -1);
    }

    public TradingCoreState placeOrder(
            TradingCoreState state,
            long userId,
            PlaceOrderCommand command,
            UUID commandId,
            long indexedOpenInterestSteps) {
        requireUserId(userId);
        if (state.orders().containsKey(command.orderId())) {
            throw new CoreStateRejectedException("DUPLICATE_ORDER_ID", "orderId already exists");
        }
        if (!command.clientOrderId().isEmpty() && state.order(userId, command.clientOrderId()) != null) {
            throw new CoreStateRejectedException("DUPLICATE_CLIENT_ORDER_ID", "clientOrderId already exists");
        }
        CoreInstrumentState instrument = requireInstrument(state, command.symbol(), command.instrumentVersion());
        if (state.treasuryState().lifecycleSettlements().containsKey(instrument.symbol())) {
            throw new CoreStateRejectedException("INSTRUMENT_SETTLED", "instrument is already settled");
        }
        validateReservationRule(state, command);
        validateInstrumentOrder(instrument, command);
        CoreUserState currentUser = state.users().getOrDefault(userId,
                CoreUserState.empty(state.productLine(), userId));
        validatePositionIdentity(state, currentUser, command);
        validateReduceOnlyCapacity(state, currentUser, command);
        validateDerivativeRiskLimits(state, instrument, currentUser, command,
                indexedOpenInterestSteps < 0 ? symbolOpenInterestSteps(state, instrument.symbol())
                        : indexedOpenInterestSteps);
        long requiredReservation = requiredReservationUnits(state, instrument, currentUser, command);
        if (command.reservedUnits() > 0 && command.reservedUnits() < requiredReservation) {
            throw new CoreStateRejectedException("INSUFFICIENT_ORDER_RESERVATION",
                    "order reservation is below deterministic margin and fee requirement");
        }
        String asset = AssetBalance.normalizeAsset(command.reservationAsset());
        AssetBalance currentBalance = currentUser.balances().getOrDefault(asset, new AssetBalance(asset, 0, 0));
        AssetBalance nextBalance = currentBalance.reserve(requiredReservation);
        OrderReservation reservation = OrderReservation.create(command.orderId(), command.symbol(),
                command.instrumentVersion(),
                command.reservationKind(), asset, requiredReservation, command.quantitySteps());
        CoreOrderState order = new CoreOrderState(command.orderId(), state.productLine(), userId,
                command.symbol(), command.instrumentVersion(), command.side(), command.priceTicks(),
                command.quantitySteps(), 0,
                command.quantitySteps(), command.reduceOnly(), command.marginMode(), command.positionSide(),
                command.orderType(), command.timeInForce(), command.postOnly(),
                command.clientOrderId(), commandId, command.makerFeeRatePpm(), command.takerFeeRatePpm(),
                CoreOrderStatus.OPEN, 1);

        Map<String, AssetBalance> balances = StateMapSupport.delta(currentUser.balances());
        balances.put(asset, nextBalance);
        Map<Long, OrderReservation> reservations = StateMapSupport.delta(currentUser.reservations());
        reservations.put(command.orderId(), reservation);
        CoreUserState nextUser = new CoreUserState(state.productLine(), userId,
                Math.incrementExact(currentUser.revision()), balances, reservations, currentUser.positions(),
                currentUser.positionMode());
        Map<Long, CoreOrderState> orders = StateMapSupport.delta(state.orders());
        orders.put(order.orderId(), order);
        Map<ClientOrderKey, Long> clientOrderIndex = StateMapSupport.delta(state.clientOrderIndex());
        if (!order.clientOrderId().isEmpty()) {
            clientOrderIndex.put(new ClientOrderKey(order.userId(), order.clientOrderId()), order.orderId());
        }
        return replaceUser(state, nextUser, orders, state.bookState(), clientOrderIndex);
    }

    public TradingCoreState cancelOrder(TradingCoreState state, long userId, CancelOrderCommand command) {
        requireUserId(userId);
        CoreOrderState currentOrder = state.orders().get(command.orderId());
        if (currentOrder == null) {
            throw new CoreStateRejectedException("ORDER_NOT_FOUND", "order does not exist");
        }
        if (currentOrder.userId() != userId) {
            throw new CoreStateRejectedException("ORDER_OWNER_MISMATCH", "order belongs to another user");
        }
        if (currentOrder.status().terminal()) {
            return state;
        }
        CoreUserState currentUser = state.users().get(userId);
        OrderReservation currentReservation = currentUser.reservations().get(command.orderId());
        if (currentReservation == null) {
            throw new IllegalStateException("open order is missing reservation");
        }
        long releaseUnits = currentReservation.remainingUnits();
        AssetBalance currentBalance = currentUser.balances().get(currentReservation.asset());
        if (currentBalance == null) {
            throw new IllegalStateException("reservation balance is missing");
        }
        AssetBalance nextBalance = releaseUnits == 0
                ? currentBalance : currentBalance.release(releaseUnits);
        OrderReservation nextReservation = releaseUnits == 0
                ? currentReservation : currentReservation.releaseAll();

        Map<String, AssetBalance> balances = StateMapSupport.delta(currentUser.balances());
        balances.put(nextBalance.asset(), nextBalance);
        Map<Long, OrderReservation> reservations = StateMapSupport.delta(currentUser.reservations());
        reservations.put(command.orderId(), nextReservation);
        CoreUserState nextUser = new CoreUserState(state.productLine(), userId,
                Math.incrementExact(currentUser.revision()), balances, reservations, currentUser.positions(),
                currentUser.positionMode());
        Map<Long, CoreOrderState> orders = StateMapSupport.delta(state.orders());
        orders.put(command.orderId(), currentOrder.cancel());
        Map<Long, Long> bookOrders = StateMapSupport.delta(state.bookState().openOrders());
        bookOrders.remove(command.orderId());
        CoreBookState bookState = new CoreBookState(state.bookState().nextPrioritySequence(), bookOrders);
        return replaceUser(state, nextUser, orders, bookState, StateMapSupport.delta(state.clientOrderIndex()));
    }

    public TradingCoreState pruneAcknowledgedTerminalReservations(
            TradingCoreState state, Collection<Long> acknowledgedOrderIds) {
        if (acknowledgedOrderIds == null || acknowledgedOrderIds.isEmpty()) {
            return state;
        }
        Map<Long, CoreUserState> users = StateMapSupport.delta(state.users());
        boolean changed = false;
        for (Long orderId : acknowledgedOrderIds) {
            if (orderId == null) continue;
            CoreOrderState order = state.orders().get(orderId);
            if (order == null || !order.status().terminal()) continue;
            CoreUserState user = users.get(order.userId());
            if (user == null) continue;
            OrderReservation reservation = user.reservations().get(orderId);
            if (reservation == null) continue;
            if (reservation.remainingUnits() != 0) {
                continue;
            }
            Map<Long, OrderReservation> reservations = StateMapSupport.delta(user.reservations());
            reservations.remove(orderId);
            users.put(order.userId(), new CoreUserState(user.productLine(), user.userId(),
                    Math.incrementExact(user.revision()), user.balances(), reservations, user.positions(),
                    user.positionMode()));
            changed = true;
        }
        if (!changed) return state;
        return new TradingCoreState(state.productLine(), Math.incrementExact(state.revision()), users,
                StateMapSupport.delta(state.orders()), state.bookState(), state.instruments(), state.riskState(), state.treasuryState(),
                StateMapSupport.delta(state.leverages()), StateMapSupport.delta(state.algoOrders()),
                StateMapSupport.delta(state.cancelAllAfterTimers()), StateMapSupport.delta(state.clientOrderIndex()),
                StateMapSupport.delta(state.triggerOrders()));
    }

    public TradingCoreState applyMatches(
            TradingCoreState state,
            long takerOrderId,
            String baseAsset,
            String quoteAsset,
            List<CoreMatch> matches) {
        if (matches == null) {
            throw new IllegalArgumentException("matches are required");
        }
        if (matches.isEmpty()) {
            CoreOrderState taker = requireOpenOrder(state.orders(), takerOrderId);
            if (!taker.timeInForce().immediate()
                    && taker.orderType() != com.surprising.aeron.protocol.CoreOrderType.MARKET) {
                Map<Long, Long> bookOrders = StateMapSupport.delta(state.bookState().openOrders());
                bookOrders.put(taker.orderId(), state.bookState().nextPrioritySequence());
                return new TradingCoreState(state.productLine(), Math.incrementExact(state.revision()),
                        StateMapSupport.delta(state.users()), StateMapSupport.delta(state.orders()),
                        new CoreBookState(Math.incrementExact(state.bookState().nextPrioritySequence()),
                                bookOrders), state.instruments(), state.riskState(), state.treasuryState(),
                        StateMapSupport.delta(state.leverages()), StateMapSupport.delta(state.algoOrders()),
                        StateMapSupport.delta(state.cancelAllAfterTimers()), StateMapSupport.delta(state.clientOrderIndex()),
                        StateMapSupport.delta(state.triggerOrders()));
            }
        }
        Map<Long, CoreUserState> users = StateMapSupport.delta(state.users());
        Map<Long, CoreOrderState> orders = StateMapSupport.delta(state.orders());
        Map<Long, Long> bookOrders = StateMapSupport.delta(state.bookState().openOrders());
        long nextPrioritySequence = state.bookState().nextPrioritySequence();
        CoreTreasuryState treasury = state.treasuryState();
        CoreOrderState taker = requireOpenOrder(orders, takerOrderId);
        CoreInstrumentState instrument = requireInstrument(state, taker.symbol(), taker.instrumentVersion());
        for (CoreMatch match : matches) {
            CoreOrderState maker = requireOpenOrder(orders, match.makerOrderId());
            if (!taker.symbol().equals(maker.symbol()) || taker.side() == maker.side()
                    || maker.userId() != match.makerUserId()) {
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
                        instrument, match.priceTicks(), match.quantitySteps(), true, takerLeverage, treasury);
                users.put(taker.userId(), takerFill.user());
                treasury = takerFill.treasury();
                long makerLeverage = state.leverages().getOrDefault(
                        new CoreLeverageKey(maker.userId(), instrument.symbol(), maker.marginMode()),
                        instrument.maxLeveragePpm());
                DerivativeFillResult makerFill = applyDerivativeFill(users.get(maker.userId()), maker,
                        instrument, match.priceTicks(), match.quantitySteps(), false, makerLeverage, treasury);
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
                        match.priceTicks(), match.quantitySteps(), buyerFeeRate, treasury);
                users.put(buyerOrder.userId(), buyerFill.user());
                treasury = buyerFill.treasury();
                SpotFillResult sellerFill = applySpotFill(users.get(sellerOrder.userId()), sellerOrder,
                        instrument, AssetBalance.normalizeAsset(baseAsset), AssetBalance.normalizeAsset(quoteAsset),
                        match.priceTicks(), match.quantitySteps(), sellerFeeRate, treasury);
                users.put(sellerOrder.userId(), sellerFill.user());
                treasury = sellerFill.treasury();
            }
            taker = taker.fill(match.quantitySteps());
            maker = maker.fill(match.quantitySteps());
            orders.put(taker.orderId(), taker);
            orders.put(maker.orderId(), maker);
            if (maker.status() == CoreOrderStatus.OPEN) {
                Long previousPrioritySequence = bookOrders.get(maker.orderId());
                if (previousPrioritySequence == null) {
                    throw new IllegalStateException("maker order missing from book state");
                }
                bookOrders.put(maker.orderId(), previousPrioritySequence);
            } else {
                bookOrders.remove(maker.orderId());
                users.put(maker.userId(), releaseTerminalReservation(users.get(maker.userId()), maker.orderId()));
            }
        }
        if (taker.status() == CoreOrderStatus.OPEN && !taker.timeInForce().immediate()
                && taker.orderType() != com.surprising.aeron.protocol.CoreOrderType.MARKET) {
            bookOrders.put(taker.orderId(), nextPrioritySequence);
            nextPrioritySequence = Math.incrementExact(nextPrioritySequence);
        } else {
            if (taker.status() == CoreOrderStatus.OPEN) {
                taker = taker.cancel();
                orders.put(taker.orderId(), taker);
            }
            users.put(taker.userId(), releaseTerminalReservation(users.get(taker.userId()), taker.orderId()));
        }
        return new TradingCoreState(state.productLine(), Math.incrementExact(state.revision()), users, orders,
                new CoreBookState(nextPrioritySequence, bookOrders), state.instruments(), state.riskState(),
                treasury, StateMapSupport.delta(state.leverages()), StateMapSupport.delta(state.algoOrders()),
                StateMapSupport.delta(state.cancelAllAfterTimers()), StateMapSupport.delta(state.clientOrderIndex()),
                StateMapSupport.delta(state.triggerOrders()));
    }

    public TradingCoreState upsertInstrument(TradingCoreState state, UpsertInstrumentCommand command) {
        CoreInstrumentState instrument = CoreInstrumentState.from(state.productLine(), command);
        CoreInstrumentState current = state.instruments().get(instrument.symbol());
        if (current != null && instrument.version() <= current.version()) {
            throw new CoreStateRejectedException("STALE_INSTRUMENT_VERSION", "instrument version must increase");
        }
        boolean openOrder = state.orders().values().stream()
                .anyMatch(order -> order.status() == CoreOrderStatus.OPEN
                        && order.symbol().equals(instrument.symbol()));
        boolean openPosition = state.users().values().stream()
                .flatMap(user -> user.positions().values().stream())
                .anyMatch(position -> position.symbol().equals(instrument.symbol())
                        && position.signedQuantitySteps() != 0);
        if (current != null && (openOrder || openPosition)) {
            throw new CoreStateRejectedException("INSTRUMENT_VERSION_IN_USE",
                    "cannot replace instrument version with open state");
        }
        Map<String, CoreInstrumentState> instruments = StateMapSupport.delta(state.instruments());
        instruments.put(instrument.symbol(), instrument);
        return new TradingCoreState(state.productLine(), Math.incrementExact(state.revision()),
                StateMapSupport.delta(state.users()), StateMapSupport.delta(state.orders()),
                state.bookState(), instruments, state.riskState(),
                state.treasuryState(), StateMapSupport.delta(state.leverages()), StateMapSupport.delta(state.algoOrders()),
                StateMapSupport.delta(state.cancelAllAfterTimers()), StateMapSupport.delta(state.clientOrderIndex()),
                StateMapSupport.delta(state.triggerOrders()));
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
        CoreInstrumentState instrument = requireInstrument(state, command.symbol(), command.instrumentVersion());
        CoreMarkPriceState current = state.riskState().markPrices().get(instrument.symbol());
        if (current != null && command.priceSequence() <= current.priceSequence()) {
            throw new CoreStateRejectedException("STALE_MARK_PRICE", "mark price sequence must increase");
        }
        Map<String, CoreMarkPriceState> marks = StateMapSupport.delta(state.riskState().markPrices());
        marks.put(instrument.symbol(), new CoreMarkPriceState(instrument.symbol(), instrument.version(),
                command.markPriceTicks(), command.priceSequence()));
        Map<String, CoreRiskState.RiskScan> scans = StateMapSupport.delta(state.riskState().scans());
        CoreRiskState.RiskScan currentScan = scans.get(instrument.symbol());
        long scanStart = currentScan != null && !currentScan.complete()
                ? currentScan.scanStartPriceSequence() : command.priceSequence();
        long lastUserId = currentScan != null && !currentScan.complete() ? currentScan.lastUserId() : 0;
        scans.put(instrument.symbol(), new CoreRiskState.RiskScan(instrument.symbol(), command.priceSequence(),
                scanStart, lastUserId, false));
        CoreRiskState risk = new CoreRiskState(marks, state.riskState().snapshots(),
                state.riskState().liquidations(), scans, state.riskState().nextLiquidationId());
        TradingCoreState withMark = new TradingCoreState(state.productLine(), Math.incrementExact(state.revision()),
                StateMapSupport.delta(state.users()), StateMapSupport.delta(state.orders()),
                state.bookState(), state.instruments(), risk, state.treasuryState(),
                StateMapSupport.delta(state.leverages()), StateMapSupport.delta(state.algoOrders()),
                StateMapSupport.delta(state.cancelAllAfterTimers()), StateMapSupport.delta(state.clientOrderIndex()),
                StateMapSupport.delta(state.triggerOrders()));
        return continueRiskScan(withMark, ContinueRiskScanCommand.DEFAULT_MAX_USERS,
                positionUserIndex, liquidationIndex);
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
        CoreRiskState.RiskScan scan = state.riskState().scan();
        if (scan.complete()) {
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
        long lastUserId = scan.lastUserId();
        int processed = 0;
        boolean complete = true;
        Iterable<CoreUserState> riskUsers = positionUserIndex == null
                ? usersAfter(state.users(), scan.lastUserId())
                : usersAfter(state, positionUserIndex.users(scan.symbol()), scan.lastUserId());
        for (CoreUserState user : riskUsers) {
            if (processed >= maxUsers) {
                complete = false;
                break;
            }
            processed++;
            lastUserId = user.userId();
            List<CorePositionState> changedPositions = positionsForSymbol(user, scan.symbol());
            if (changedPositions.isEmpty()) {
                continue;
            }
            for (CorePositionState position : changedPositions) {
                if (position.marginMode() == CoreMarginMode.ISOLATED) {
                    nextLiquidationId = updateIsolatedRisk(state, user, position, instrument, mark,
                            snapshots, liquidations, nextLiquidationId, liquidationIndex);
                }
            }
            if (changedPositions.stream().anyMatch(position -> position.marginMode() == CoreMarginMode.CROSS)) {
                nextLiquidationId = updateCrossRisk(state, user, instrument.settleAsset(), snapshots,
                        liquidations, nextLiquidationId, liquidationIndex);
            }
        }
        Map<String, CoreRiskState.RiskScan> scans = StateMapSupport.delta(state.riskState().scans());
        CoreRiskState.RiskScan nextScan = complete && scan.scanStartPriceSequence() != scan.priceSequence()
                ? new CoreRiskState.RiskScan(scan.symbol(), scan.priceSequence(), scan.priceSequence(), 0, false)
                : new CoreRiskState.RiskScan(scan.symbol(), scan.priceSequence(), scan.scanStartPriceSequence(),
                        lastUserId, complete);
        scans.put(scan.symbol(), nextScan);
        CoreRiskState nextRisk = new CoreRiskState(state.riskState().markPrices(), snapshots, liquidations,
                scans, nextLiquidationId);
        return new TradingCoreState(state.productLine(), Math.incrementExact(state.revision()),
                StateMapSupport.delta(state.users()), StateMapSupport.delta(state.orders()),
                state.bookState(), state.instruments(), nextRisk, state.treasuryState(),
                StateMapSupport.delta(state.leverages()), StateMapSupport.delta(state.algoOrders()),
                StateMapSupport.delta(state.cancelAllAfterTimers()), StateMapSupport.delta(state.clientOrderIndex()),
                StateMapSupport.delta(state.triggerOrders()));
    }

    private long updateIsolatedRisk(TradingCoreState state, CoreUserState user, CorePositionState position,
                                    CoreInstrumentState instrument, CoreMarkPriceState mark,
                                    Map<String, CoreRiskSnapshot> snapshots,
                                    Map<Long, CoreLiquidationState> liquidations, long nextLiquidationId,
                                    LiquidationIndex liquidationIndex) {
        PositionRisk risk = positionRisk(position, instrument, mark);
        long equity = Math.addExact(position.positionMarginUnits(), risk.unrealizedPnlUnits());
        long ratio = riskRatio(risk.maintenanceMarginUnits(), equity);
        CoreRiskStatus status = riskStatus(ratio);
        CoreRiskSnapshot snapshot = new CoreRiskSnapshot(user.userId(), position.symbol(), position.positionSide(),
                mark.priceSequence(), equity, risk.unrealizedPnlUnits(), risk.maintenanceMarginUnits(), ratio, status);
        snapshots.put(snapshot.key(), snapshot);
        return ensureLiquidation(user.userId(), position, instrument, mark.priceSequence(), status,
                liquidations, nextLiquidationId, liquidationIndex);
    }

    private long updateCrossRisk(TradingCoreState state, CoreUserState user, String settleAsset,
                                 Map<String, CoreRiskSnapshot> snapshots,
                                 Map<Long, CoreLiquidationState> liquidations, long nextLiquidationId,
                                 LiquidationIndex liquidationIndex) {
        List<PositionRisk> risks = user.positions().values().stream()
                .filter(position -> position.signedQuantitySteps() != 0)
                .filter(position -> position.marginMode() == CoreMarginMode.CROSS)
                .filter(position -> position.marginAsset().equals(settleAsset))
                .map(position -> {
                    CoreInstrumentState instrument = state.instruments().get(position.symbol());
                    CoreMarkPriceState mark = state.riskState().markPrices().get(position.symbol());
                    return instrument == null || mark == null ? null : positionRisk(position, instrument, mark);
                })
                .filter(java.util.Objects::nonNull)
                .toList();
        long unrealized = 0;
        long maintenance = 0;
        for (PositionRisk risk : risks) {
            unrealized = Math.addExact(unrealized, risk.unrealizedPnlUnits());
            maintenance = Math.addExact(maintenance, risk.maintenanceMarginUnits());
        }
        long wallet = crossWalletBalance(state, user, settleAsset);
        long equity = Math.addExact(wallet, unrealized);
        long ratio = riskRatio(maintenance, equity);
        CoreRiskStatus status = riskStatus(ratio);
        for (PositionRisk risk : risks) {
            CoreRiskSnapshot snapshot = new CoreRiskSnapshot(user.userId(), risk.position().symbol(),
                    risk.position().positionSide(), risk.mark().priceSequence(), equity, risk.unrealizedPnlUnits(),
                    risk.maintenanceMarginUnits(), ratio, status);
            snapshots.put(snapshot.key(), snapshot);
            nextLiquidationId = ensureLiquidation(user.userId(), risk.position(), risk.instrument(),
                    risk.mark().priceSequence(), status, liquidations, nextLiquidationId, liquidationIndex);
        }
        return nextLiquidationId;
    }

    private PositionRisk positionRisk(CorePositionState position, CoreInstrumentState instrument,
                                      CoreMarkPriceState mark) {
        long unrealized = PerpetualContractMath.unrealizedPnlUnits(instrument.contractType(),
                position.signedQuantitySteps(), position.entryPriceTicks(), mark.markPriceTicks(),
                instrument.notionalMultiplierUnits(), instrument.priceTickUnits(), instrument.settleScaleUnits());
        long maintenance = CoreContractMath.maintenanceMarginUnits(instrument,
                position.signedQuantitySteps(), mark.markPriceTicks());
        return new PositionRisk(position, instrument, mark, unrealized, maintenance);
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
        if (status != CoreRiskStatus.LIQUIDATION) {
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
                position.marginMode(), position.positionSide(), instrument.version(), priceSequence,
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

    private long crossWalletBalance(TradingCoreState state, CoreUserState user, String asset) {
        AssetBalance balance = user.balances().get(asset);
        long wallet = balance == null ? 0 : balance.totalUnits();
        for (CorePositionState position : user.positions().values()) {
            if (position.marginMode() == CoreMarginMode.ISOLATED && position.marginAsset().equals(asset)) {
                wallet = Math.subtractExact(wallet, position.positionMarginUnits());
            }
        }
        for (OrderReservation reservation : user.reservations().values()) {
            CoreOrderState order = state.orders().get(reservation.orderId());
            if (order != null && order.marginMode() == CoreMarginMode.ISOLATED
                    && reservation.asset().equals(asset)) {
                wallet = Math.subtractExact(wallet, reservation.remainingUnits());
            }
        }
        if (wallet < 0) throw new IllegalStateException("isolated margin exceeds wallet balance");
        return wallet;
    }

    private record PositionRisk(CorePositionState position, CoreInstrumentState instrument,
                                CoreMarkPriceState mark, long unrealizedPnlUnits,
                                long maintenanceMarginUnits) {}

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
        CoreInstrumentState instrument = requireInstrument(state, command.symbol(), command.instrumentVersion());
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
        boolean chunked = indexedUserIds != null && chunkCommandId != null;
        if (chunked) {
            if (previousProgress == null && command.cursorUserId() != 0) {
                throw new CoreStateRejectedException("INVALID_COMMAND", "funding cursor must start at zero");
            }
            if (previousProgress != null && (previousProgress.settlementId() != command.settlementId()
                    || previousProgress.instrumentVersion() != command.instrumentVersion()
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
                long positionDelta = CoreContractMath.fundingDeltaUnits(instrument,
                        position.signedQuantitySteps(), mark.markPriceTicks(), command.fundingRatePpm());
                positionDeltas.add(positionDelta);
                delta = Math.addExact(delta, positionDelta);
            }
            if (positions.isEmpty()) continue;
            CashResult result = applyCash(requireBalance(user, instrument.settleAsset()), delta);
            if (result.appliedDelta() != 0) {
                Map<String, AssetBalance> balances = StateMapSupport.delta(user.balances());
                balances.put(instrument.settleAsset(), result.balance());
                users.put(user.userId(), new CoreUserState(user.productLine(), user.userId(),
                        Math.incrementExact(user.revision()), balances, user.reservations(), user.positions(),
                        user.positionMode()));
                treasury = treasury.adjustInsurance(instrument.settleAsset(), Math.negateExact(result.appliedDelta()));
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
                            instrument.contractType(), position.signedQuantitySteps(), mark.markPriceTicks(),
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
                    command.settlementId(), command.instrumentVersion(), command.fundingRatePpm(),
                    nextCursorUserId, progressCommandId));
        }
        var progress = new com.surprising.aeron.protocol.CoreFundingProgressView(
                command.settlementId(), complete, nextCursorUserId, selectedUserIds.size());
        return new FundingApplication(new TradingCoreState(state.productLine(), Math.incrementExact(state.revision()), users,
                StateMapSupport.delta(state.orders()), state.bookState(), state.instruments(), state.riskState(), treasury,
                StateMapSupport.delta(state.leverages()), StateMapSupport.delta(state.algoOrders()),
                StateMapSupport.delta(state.cancelAllAfterTimers()), StateMapSupport.delta(state.clientOrderIndex()),
                StateMapSupport.delta(state.triggerOrders())), payments,
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
        CoreInstrumentState instrument = requireInstrument(state, command.symbol(), command.instrumentVersion());
        long previousSettlement = state.treasuryState().lifecycleSettlements()
                .getOrDefault(instrument.symbol(), 0L);
        if (command.settlementId() <= previousSettlement) {
            throw new CoreStateRejectedException("STALE_SETTLEMENT_ID", "lifecycle settlement id must increase");
        }
        if (!instrument.contractType().isDelivery() && !instrument.contractType().isOption()) {
            throw new CoreStateRejectedException("PRODUCT_LINE_UNSUPPORTED",
                    "instrument settlement requires delivery or option product");
        }
        if (instrument.contractType().isDelivery() && command.settlementPriceTicks() <= 0) {
            throw new CoreStateRejectedException("INVALID_SETTLEMENT_PRICE", "delivery price must be positive");
        }
        CoreTreasuryState.LifecycleProgress previousProgress = state.treasuryState()
                .lifecycleProgress(instrument.symbol());
        boolean chunked = indexedUserIds != null && chunkCommandId != null;
        if (chunked) {
            if (previousProgress == null && command.cursorUserId() != 0) {
                throw new CoreStateRejectedException("INVALID_COMMAND", "settlement cursor must start at zero");
            }
            if (previousProgress != null && (previousProgress.settlementId() != command.settlementId()
                    || previousProgress.instrumentVersion() != command.instrumentVersion()
                    || previousProgress.settlementPriceTicks() != command.settlementPriceTicks()
                    || previousProgress.optionCashUnitsPerContract() != command.optionCashUnitsPerContract()
                    || previousProgress.nextCursorUserId() != command.cursorUserId())) {
                throw new CoreStateRejectedException("INVALID_COMMAND", "settlement cursor does not match progress");
            }
        }
        TradingCoreState canceled = !chunked || previousProgress == null
                ? cancelSymbolOrders(state, instrument.symbol(), activeOrderIndex) : state;
        Map<Long, CoreUserState> users = StateMapSupport.delta(canceled.users());
        CoreTreasuryState treasury = canceled.treasuryState();
        java.util.ArrayList<Long> selectedUserIds = new java.util.ArrayList<>();
        boolean moreUsers = false;
        if (!chunked) {
            if (indexedUserIds == null) canceled.users().keySet().forEach(selectedUserIds::add);
            else indexedUserIds.forEach(selectedUserIds::add);
        } else {
            for (Long userId : indexedUserIds) {
                if (userId == null || userId <= command.cursorUserId()) continue;
                if (selectedUserIds.size() < command.maxUsers()) selectedUserIds.add(userId);
                else {
                    moreUsers = true;
                    break;
                }
            }
        }
        for (Long userId : selectedUserIds) {
            CoreUserState user = canceled.user(userId);
            if (user == null) continue;
            List<CorePositionState> settling = positionsForSymbol(user, instrument.symbol());
            if (settling.isEmpty()) continue;
            AssetBalance balance = requireBalance(user, instrument.settleAsset());
            Map<String, AssetBalance> balances = StateMapSupport.delta(user.balances());
            Map<String, CorePositionState> positions = StateMapSupport.delta(user.positions());
            for (CorePositionState position : settling) {
                if (position.positionMarginUnits() > 0) balance = balance.release(position.positionMarginUnits());
                long cashDelta = instrument.contractType().isOption()
                        ? Math.multiplyExact(command.optionCashUnitsPerContract(), position.signedQuantitySteps())
                        : CoreContractMath.pnlUnits(instrument, position.signedQuantitySteps(),
                        position.entryPriceTicks(), command.settlementPriceTicks());
                CashResult result = applyCash(balance, cashDelta);
                balance = result.balance();
                treasury = treasury.adjustInsurance(instrument.settleAsset(),
                        Math.negateExact(result.appliedDelta()));
                positions.put(position.key(), new CorePositionState(instrument.symbol(), instrument.settleAsset(),
                        position.marginMode(), position.positionSide(), 0, 0, 0, 0,
                        Math.addExact(position.realizedPnlUnits(), cashDelta), 0));
            }
            balances.put(instrument.settleAsset(), balance);
            users.put(user.userId(), new CoreUserState(user.productLine(), user.userId(),
                    Math.incrementExact(user.revision()), balances, user.reservations(), positions,
                    user.positionMode()));
        }
        boolean complete = !chunked || !moreUsers;
        long nextCursorUserId = complete ? 0 : selectedUserIds.getLast();
        if (complete) {
            treasury = treasury.recordLifecycle(instrument.symbol(), command.settlementId());
        } else {
            treasury = treasury.withLifecycleProgress(instrument.symbol(), new CoreTreasuryState.LifecycleProgress(
                    command.settlementId(), command.instrumentVersion(), command.settlementPriceTicks(),
                    command.optionCashUnitsPerContract(), nextCursorUserId, chunkCommandId));
        }
        TradingCoreState next = new TradingCoreState(canceled.productLine(), Math.incrementExact(canceled.revision()), users,
                StateMapSupport.delta(canceled.orders()), canceled.bookState(), canceled.instruments(), canceled.riskState(), treasury,
                StateMapSupport.delta(canceled.leverages()), StateMapSupport.delta(canceled.algoOrders()),
                StateMapSupport.delta(canceled.cancelAllAfterTimers()), StateMapSupport.delta(canceled.clientOrderIndex()),
                StateMapSupport.delta(canceled.triggerOrders()));
        return new SettlementApplication(next, new com.surprising.aeron.protocol.CoreSettlementProgressView(
                command.settlementId(), complete, nextCursorUserId, selectedUserIds.size()));
    }

    public record SettlementApplication(TradingCoreState state,
                                         com.surprising.aeron.protocol.CoreSettlementProgressView progress) {
        public SettlementApplication {
            if (progress == null) throw new IllegalArgumentException("settlement progress is required");
        }
    }

    public TradingCoreState executeLiquidation(TradingCoreState state, ExecuteLiquidationCommand command) {
        CoreLiquidationState liquidation = state.riskState().liquidations().get(command.liquidationId());
        if (liquidation == null) {
            throw new CoreStateRejectedException("LIQUIDATION_NOT_FOUND", "liquidation plan does not exist");
        }
        if (liquidation.status() != CoreLiquidationState.Status.PLANNED) {
            throw new CoreStateRejectedException("LIQUIDATION_STATE_CONFLICT", "liquidation is not planned");
        }
        validateLiquidationPrice(state, liquidation, command);
        if (!isLiquidationExecutable(state, liquidation)) {
            return cancelLiquidation(state, liquidation);
        }
        CoreInstrumentState instrument = requireInstrument(state, liquidation.symbol(),
                liquidation.instrumentVersion());
        CoreUserState user = state.user(liquidation.userId());
        String positionKey = positionKey(liquidation.symbol(), liquidation.positionSide());
        CorePositionState position = user.positions().get(positionKey);
        TradingCoreState canceled = cancelUserSymbolOrders(state, user.userId(), liquidation.symbol());
        user = canceled.user(user.userId());
        position = user.positions().get(positionKey);
        AssetBalance balance = requireBalance(user, instrument.settleAsset());
        if (position.positionMarginUnits() > 0) {
            balance = balance.release(position.positionMarginUnits());
        }
        long pnl = instrument.contractType().isOption() ? 0
                : CoreContractMath.pnlUnits(instrument, position.signedQuantitySteps(),
                position.entryPriceTicks(), command.executionPriceTicks());
        CashResult cash = applyCash(balance, pnl);
        long uncovered = pnl < 0 ? Math.subtractExact(Math.negateExact(pnl),
                Math.negateExact(Math.min(0, cash.appliedDelta()))) : 0;
        long feeDue = Math.negateExact(CoreContractMath.feeDeltaUnits(instrument,
                command.executionPriceTicks(), liquidation.closeQuantitySteps(), command.liquidationFeeRatePpm()));
        CashResult feeCash = applyCash(cash.balance(), Math.negateExact(feeDue));
        long collectedFee = Math.negateExact(feeCash.appliedDelta());
        CoreTreasuryState treasury = canceled.treasuryState()
                .adjustInsurance(instrument.settleAsset(),
                        Math.addExact(Math.negateExact(cash.appliedDelta()), collectedFee));
        Map<String, AssetBalance> balances = StateMapSupport.delta(user.balances());
        balances.put(instrument.settleAsset(), feeCash.balance());
        Map<String, CorePositionState> positions = StateMapSupport.delta(user.positions());
        positions.put(positionKey, new CorePositionState(instrument.symbol(), instrument.settleAsset(),
                position.marginMode(), position.positionSide(), 0, 0, 0, 0,
                Math.addExact(position.realizedPnlUnits(), pnl), 0));
        CoreUserState nextUser = new CoreUserState(user.productLine(), user.userId(),
                Math.incrementExact(user.revision()), balances, user.reservations(), positions, user.positionMode());
        Map<Long, CoreUserState> users = StateMapSupport.delta(canceled.users());
        users.put(nextUser.userId(), nextUser);
        Map<Long, CoreLiquidationState> liquidations = StateMapSupport.delta(canceled.riskState().liquidations());
        liquidations.put(liquidation.liquidationId(), liquidation.executed(uncovered,
                command.executionPriceTicks(), command.liquidationFeeRatePpm(), collectedFee));
        CoreRiskState risk = new CoreRiskState(canceled.riskState().markPrices(), canceled.riskState().snapshots(),
                liquidations, canceled.riskState().scans(), canceled.riskState().nextLiquidationId());
        return new TradingCoreState(canceled.productLine(), Math.incrementExact(canceled.revision()), users,
                StateMapSupport.delta(canceled.orders()), canceled.bookState(), canceled.instruments(), risk, treasury,
                StateMapSupport.delta(canceled.leverages()), StateMapSupport.delta(canceled.algoOrders()),
                StateMapSupport.delta(canceled.cancelAllAfterTimers()), StateMapSupport.delta(canceled.clientOrderIndex()),
                StateMapSupport.delta(canceled.triggerOrders()));
    }

    public boolean isLiquidationExecutable(TradingCoreState state, ExecuteLiquidationCommand command) {
        CoreLiquidationState liquidation = state.riskState().liquidations().get(command.liquidationId());
        if (liquidation == null || liquidation.status() != CoreLiquidationState.Status.PLANNED) return false;
        validateLiquidationPrice(state, liquidation, command);
        return isLiquidationExecutable(state, liquidation);
    }

    private static boolean isLiquidationExecutable(TradingCoreState state, CoreLiquidationState liquidation) {
        CoreUserState user = state.user(liquidation.userId());
        CorePositionState position = user == null ? null
                : user.positions().get(positionKey(liquidation.symbol(), liquidation.positionSide()));
        CoreRiskSnapshot risk = state.riskState().snapshots().get(
                riskKey(liquidation.userId(), liquidation.symbol(), liquidation.positionSide()));
        return position != null && position.instrumentVersion() == liquidation.instrumentVersion()
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
                liquidations, state.riskState().scans(), state.riskState().nextLiquidationId());
        return new TradingCoreState(state.productLine(), Math.incrementExact(state.revision()),
                StateMapSupport.delta(state.users()), StateMapSupport.delta(state.orders()),
                state.bookState(), state.instruments(), risk, state.treasuryState(),
                StateMapSupport.delta(state.leverages()), StateMapSupport.delta(state.algoOrders()),
                StateMapSupport.delta(state.cancelAllAfterTimers()), StateMapSupport.delta(state.clientOrderIndex()),
                StateMapSupport.delta(state.triggerOrders()));
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
                liquidation.instrumentVersion());
        CoreLiquidationState.Status nextStatus;
        CoreTreasuryState treasury = state.treasuryState();
        switch (command.resolution()) {
            case INSURANCE -> {
                if (liquidation.status() != CoreLiquidationState.Status.INSURANCE_REQUIRED) {
                    throw new CoreStateRejectedException("LIQUIDATION_STATE_CONFLICT",
                            "insurance resolution requires insurance state");
                }
                if (command.coveredUnits() <= 0 || command.coveredUnits() > liquidation.deficitUnits()) {
                    throw new CoreStateRejectedException("INSURANCE_COVER_EXCEEDS_DEFICIT",
                            "insurance coverage must be within liquidation deficit");
                }
                long available = treasury.insuranceBalances().getOrDefault(instrument.settleAsset(), 0L);
                if (command.coveredUnits() > available) {
                    throw new CoreStateRejectedException("INSUFFICIENT_AVAILABLE_BALANCE",
                            "insurance fund balance is insufficient");
                }
                treasury = treasury.adjustInsurance(instrument.settleAsset(),
                        Math.negateExact(command.coveredUnits()));
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
                nextStatus = CoreLiquidationState.Status.COMPLETED;
            }
            default -> throw new IllegalStateException("unknown liquidation resolution");
        }
        Map<Long, CoreLiquidationState> liquidations = StateMapSupport.delta(state.riskState().liquidations());
        CoreLiquidationState nextLiquidation = command.resolution() == ResolveLiquidationCommand.Resolution.COMPLETED
                ? liquidation.withStatus(nextStatus) : liquidation.covered(command.coveredUnits(), nextStatus);
        liquidations.put(liquidation.liquidationId(), nextLiquidation);
        CoreRiskState risk = new CoreRiskState(state.riskState().markPrices(), state.riskState().snapshots(),
                liquidations, state.riskState().scans(), state.riskState().nextLiquidationId());
        return new TradingCoreState(state.productLine(), Math.incrementExact(state.revision()),
                StateMapSupport.delta(state.users()), StateMapSupport.delta(state.orders()),
                state.bookState(), state.instruments(), risk, treasury, StateMapSupport.delta(state.leverages()),
                StateMapSupport.delta(state.algoOrders()), StateMapSupport.delta(state.cancelAllAfterTimers()),
                StateMapSupport.delta(state.clientOrderIndex()), StateMapSupport.delta(state.triggerOrders()));
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
                StateMapSupport.delta(state.users()), StateMapSupport.delta(state.orders()),
                state.bookState(), state.instruments(), state.riskState(), treasury,
                StateMapSupport.delta(state.leverages()), StateMapSupport.delta(state.algoOrders()),
                StateMapSupport.delta(state.cancelAllAfterTimers()), StateMapSupport.delta(state.clientOrderIndex()),
                StateMapSupport.delta(state.triggerOrders()));
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
                liquidation.instrumentVersion());
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
        long targetCashDelta = Math.subtractExact(coverCapacity, command.coveredUnits());
        if (targetCashDelta > 0) balance = balance.credit(targetCashDelta);
        Map<String, AssetBalance> balances = StateMapSupport.delta(target.balances());
        balances.put(instrument.settleAsset(), balance);
        long nextEntryValue = remainingAbs == 0 ? 0
                : proportional(position.entryValueTicks(), remainingAbs, currentAbs);
        Map<String, CorePositionState> positions = StateMapSupport.delta(target.positions());
        positions.put(positionKey, new CorePositionState(position.symbol(), position.marginAsset(),
                position.marginMode(), position.positionSide(), remainingAbs == 0 ? 0 : position.instrumentVersion(),
                nextQuantity, remainingAbs == 0 ? 0 : position.entryPriceTicks(), nextEntryValue,
                Math.addExact(position.realizedPnlUnits(), coverCapacity),
                Math.subtractExact(position.positionMarginUnits(), releasedMargin)));
        CoreUserState nextTarget = new CoreUserState(target.productLine(), target.userId(),
                Math.incrementExact(target.revision()), balances, target.reservations(), positions,
                target.positionMode());
        Map<Long, CoreUserState> users = StateMapSupport.delta(state.users());
        users.put(nextTarget.userId(), nextTarget);
        CoreLiquidationState.Status nextStatus = command.coveredUnits() == liquidation.deficitUnits()
                ? CoreLiquidationState.Status.COMPLETED : CoreLiquidationState.Status.ADL_REQUIRED;
        Map<Long, CoreLiquidationState> liquidations = StateMapSupport.delta(state.riskState().liquidations());
        liquidations.put(liquidation.liquidationId(), liquidation.covered(command.coveredUnits(), nextStatus));
        CoreRiskState risk = new CoreRiskState(state.riskState().markPrices(), state.riskState().snapshots(),
                liquidations, state.riskState().scans(), state.riskState().nextLiquidationId());
        return new TradingCoreState(state.productLine(), Math.incrementExact(state.revision()), users,
                StateMapSupport.delta(state.orders()), state.bookState(), state.instruments(), risk, state.treasuryState(),
                StateMapSupport.delta(state.leverages()), StateMapSupport.delta(state.algoOrders()),
                StateMapSupport.delta(state.cancelAllAfterTimers()), StateMapSupport.delta(state.clientOrderIndex()),
                StateMapSupport.delta(state.triggerOrders()));
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
                if (instrument == null || mark == null || !instrument.contractType().isPerpetual()
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

    private TradingCoreState cancelUserSymbolOrders(TradingCoreState state, long userId, String symbol) {
        CoreUserState user = state.user(userId);
        if (user == null) return state;
        List<CoreOrderState> orders = userOrders(state, user).stream()
                .filter(order -> order.status() == CoreOrderStatus.OPEN
                        && order.symbol().equals(symbol))
                .toList();
        return cancelOrders(state, orders);
    }

    private TradingCoreState cancelSymbolOrders(TradingCoreState state, String symbol,
                                                ActiveOrderIndex activeOrderIndex) {
        List<CoreOrderState> openOrders = activeOrderIndex == null
                ? state.orders().values().stream()
                .filter(order -> order.status() == CoreOrderStatus.OPEN)
                .filter(order -> order.symbol().equals(symbol))
                .toList()
                : activeOrderIndex.ids(symbol).stream().map(state::order)
                .filter(java.util.Objects::nonNull).toList();
        return cancelOrders(state, openOrders);
    }

    private TradingCoreState cancelOrders(TradingCoreState state, List<CoreOrderState> openOrders) {
        if (openOrders == null || openOrders.isEmpty()) return state;
        Map<Long, CoreUserState> users = StateMapSupport.delta(state.users());
        Map<Long, CoreOrderState> orders = StateMapSupport.delta(state.orders());
        Map<Long, Long> bookOrders = StateMapSupport.delta(state.bookState().openOrders());
        boolean changed = false;
        for (CoreOrderState order : openOrders) {
            CoreOrderState currentOrder = orders.get(order.orderId());
            if (currentOrder == null || currentOrder.status() != CoreOrderStatus.OPEN) continue;
            CoreUserState currentUser = users.get(currentOrder.userId());
            if (currentUser == null) {
                throw new IllegalStateException("order owner is missing orderId=" + currentOrder.orderId());
            }
            OrderReservation reservation = requireReservation(currentUser, currentOrder.orderId());
            long releaseUnits = reservation.remainingUnits();
            AssetBalance balance = requireBalance(currentUser, reservation.asset());
            Map<String, AssetBalance> balances = StateMapSupport.delta(currentUser.balances());
            if (releaseUnits != 0) balances.put(reservation.asset(), balance.release(releaseUnits));
            Map<Long, OrderReservation> reservations = StateMapSupport.delta(currentUser.reservations());
            reservations.put(currentOrder.orderId(), reservation.releaseAll());
            users.put(currentUser.userId(), new CoreUserState(currentUser.productLine(), currentUser.userId(),
                    Math.incrementExact(currentUser.revision()), balances, reservations, currentUser.positions(),
                    currentUser.positionMode()));
            orders.put(currentOrder.orderId(), currentOrder.cancel());
            bookOrders.remove(currentOrder.orderId());
            changed = true;
        }
        if (!changed) return state;
        return new TradingCoreState(state.productLine(), Math.incrementExact(state.revision()), users, orders,
                new CoreBookState(state.bookState().nextPrioritySequence(), bookOrders), state.instruments(),
                state.riskState(), state.treasuryState(), StateMapSupport.delta(state.leverages()),
                StateMapSupport.delta(state.algoOrders()), StateMapSupport.delta(state.cancelAllAfterTimers()),
                StateMapSupport.delta(state.clientOrderIndex()), StateMapSupport.delta(state.triggerOrders()));
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
        if (instrument.version() != version) {
            throw new CoreStateRejectedException("INSTRUMENT_VERSION_CONFLICT", "instrument version differs");
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

    private static SpotFillResult applySpotFill(
            CoreUserState user,
            CoreOrderState order,
            CoreInstrumentState instrument,
            String baseAsset,
            String quoteAsset,
            long fillPriceTicks,
            long fillQuantitySteps,
            long feeRatePpm,
            CoreTreasuryState treasury) {
        OrderReservation reservation = requireReservation(user, order.orderId());
        String debitAsset = order.side() == CoreOrderSide.BUY ? quoteAsset : baseAsset;
        if (!reservation.asset().equals(debitAsset)) {
            throw new IllegalStateException("spot fill debit asset does not match reservation");
        }
        long quoteUnits = Math.multiplyExact(fillPriceTicks, fillQuantitySteps);
        long feeDelta = CoreContractMath.feeDeltaUnits(instrument, fillPriceTicks, fillQuantitySteps, feeRatePpm);
        Map<String, AssetBalance> balances = StateMapSupport.delta(user.balances());
        long reservationDebit;
        if (order.side() == CoreOrderSide.BUY) {
            reservationDebit = Math.addExact(quoteUnits, Math.max(0, Math.negateExact(feeDelta)));
            AssetBalance quoteBalance = requireBalance(user, quoteAsset).consumeLocked(quoteUnits);
            quoteBalance = applySpotFee(quoteBalance, feeDelta, true);
            balances.put(quoteAsset, quoteBalance);
            AssetBalance baseBalance = balances.getOrDefault(baseAsset, new AssetBalance(baseAsset, 0, 0));
            balances.put(baseAsset, baseBalance.credit(fillQuantitySteps));
        } else {
            reservationDebit = fillQuantitySteps;
            balances.put(baseAsset, requireBalance(user, baseAsset).consumeLocked(fillQuantitySteps));
            AssetBalance quoteBalance = balances.getOrDefault(quoteAsset, new AssetBalance(quoteAsset, 0, 0))
                    .credit(quoteUnits);
            balances.put(quoteAsset, applySpotFee(quoteBalance, feeDelta, false));
        }
        Map<Long, OrderReservation> reservations = StateMapSupport.delta(user.reservations());
        reservations.put(order.orderId(), reservation.consume(reservationDebit));
        CoreUserState nextUser = new CoreUserState(user.productLine(), user.userId(),
                Math.incrementExact(user.revision()), balances, reservations, user.positions(), user.positionMode());
        return new SpotFillResult(nextUser, treasury.adjustFee(quoteAsset, Math.negateExact(feeDelta)));
    }

    private static AssetBalance applySpotFee(AssetBalance balance, long feeDelta, boolean consumeLocked) {
        if (feeDelta < 0) {
            long feeUnits = Math.negateExact(feeDelta);
            return consumeLocked ? balance.consumeLocked(feeUnits) : balance.adjustAvailable(feeDelta);
        }
        return feeDelta == 0 ? balance : balance.credit(feeDelta);
    }

    private static DerivativeFillResult applyDerivativeFill(
            CoreUserState user,
            CoreOrderState order,
            CoreInstrumentState instrument,
            long fillPriceTicks,
            long fillQuantitySteps,
            boolean taker,
            long leveragePpm,
            CoreTreasuryState treasury) {
        OrderReservation reservation = requireReservation(user, order.orderId());
        String positionKey = positionKey(order.symbol(), order.positionSide());
        CorePositionState current = user.positions().get(positionKey);
        long signedFill = order.side() == CoreOrderSide.BUY ? fillQuantitySteps : Math.negateExact(fillQuantitySteps);
        long currentQuantity = current == null ? 0 : current.signedQuantitySteps();
        long currentAbs = Math.absExact(currentQuantity);
        boolean opposite = currentQuantity != 0 && Long.signum(currentQuantity) != Long.signum(signedFill);
        long closeSteps = opposite ? Math.min(currentAbs, fillQuantitySteps) : 0;
        long openSteps = Math.subtractExact(fillQuantitySteps, closeSteps);
        if (order.reduceOnly() && openSteps != 0) {
            throw new CoreStateRejectedException("REDUCE_ONLY_CAPACITY_EXCEEDED",
                    "reduce-only fill would create reverse exposure");
        }
        long releasedMargin = current == null || closeSteps == 0 ? 0
                : proportional(current.positionMarginUnits(), closeSteps, currentAbs);
        long nextQuantity = Math.addExact(currentQuantity, signedFill);
        long remainingMargin = Math.subtractExact(current == null ? 0 : current.positionMarginUnits(), releasedMargin);
        long requiredMargin = positionMarginRequirement(instrument, nextQuantity, fillPriceTicks, leveragePpm);
        long marginIncrease = Math.max(0, Math.subtractExact(requiredMargin, remainingMargin));
        long premiumDelta = instrument.contractType().isOption()
                ? (order.side() == CoreOrderSide.BUY ? Math.negateExact(
                CoreContractMath.optionPremiumUnits(instrument, fillPriceTicks, fillQuantitySteps))
                : CoreContractMath.optionPremiumUnits(instrument, fillPriceTicks, fillQuantitySteps)) : 0;
        long feeRatePpm = taker ? order.takerFeeRatePpm() : order.makerFeeRatePpm();
        long feeDelta = CoreContractMath.feeDeltaUnits(instrument, fillPriceTicks, fillQuantitySteps, feeRatePpm);
        long reservationDebit = Math.addExact(marginIncrease,
                Math.addExact(Math.max(0, Math.negateExact(premiumDelta)), Math.max(0, Math.negateExact(feeDelta))));
        OrderReservation nextReservation = reservationDebit == 0 ? reservation : reservation.consume(reservationDebit);
        Map<String, AssetBalance> balances = StateMapSupport.delta(user.balances());
        AssetBalance balance = requireBalance(user, instrument.settleAsset());
        if (releasedMargin > 0) {
            balance = balance.release(releasedMargin);
        }
        long realizedPnl = 0;
        if (closeSteps > 0 && !instrument.contractType().isOption()) {
            long signedClose = currentQuantity > 0 ? closeSteps : Math.negateExact(closeSteps);
            realizedPnl = CoreContractMath.pnlUnits(instrument, signedClose,
                    current.entryPriceTicks(), fillPriceTicks);
        }
        CashResult pnlCash = applyCash(balance, realizedPnl);
        balance = pnlCash.balance();
        treasury = treasury.adjustInsurance(instrument.settleAsset(), Math.negateExact(pnlCash.appliedDelta()));
        if (premiumDelta < 0) {
            balance = balance.consumeLocked(Math.negateExact(premiumDelta));
        } else if (premiumDelta > 0) {
            balance = balance.credit(premiumDelta);
        }
        if (feeDelta < 0) {
            balance = balance.consumeLocked(Math.negateExact(feeDelta));
        } else if (feeDelta > 0) {
            balance = balance.credit(feeDelta);
        }
        treasury = treasury.adjustFee(instrument.settleAsset(), Math.negateExact(feeDelta));
        balances.put(instrument.settleAsset(), balance);
        long nextEntryPrice;
        long nextEntryValue;
        if (nextQuantity == 0) {
            nextEntryPrice = 0;
            nextEntryValue = 0;
        } else if (currentQuantity == 0 || Long.signum(nextQuantity) != Long.signum(currentQuantity)) {
            nextEntryPrice = fillPriceTicks;
            nextEntryValue = Math.multiplyExact(Math.absExact(nextQuantity), fillPriceTicks);
        } else if (Long.signum(signedFill) == Long.signum(currentQuantity)) {
            nextEntryPrice = CoreContractMath.weightedEntryPrice(instrument, currentAbs,
                    current.entryPriceTicks(), fillQuantitySteps, fillPriceTicks);
            nextEntryValue = Math.multiplyExact(Math.absExact(nextQuantity), nextEntryPrice);
        } else {
            nextEntryPrice = current.entryPriceTicks();
            nextEntryValue = Math.multiplyExact(Math.absExact(nextQuantity), nextEntryPrice);
        }
        long nextMargin = Math.addExact(remainingMargin, marginIncrease);
        CorePositionState position = new CorePositionState(order.symbol(), reservation.asset(), order.marginMode(),
                order.positionSide(), nextQuantity == 0 ? 0 : order.instrumentVersion(), nextQuantity,
                nextEntryPrice, nextEntryValue,
                Math.addExact(current == null ? 0 : current.realizedPnlUnits(), realizedPnl), nextMargin);
        Map<Long, OrderReservation> reservations = StateMapSupport.delta(user.reservations());
        reservations.put(order.orderId(), nextReservation);
        Map<String, CorePositionState> positions = StateMapSupport.delta(user.positions());
        positions.put(positionKey, position);
        return new DerivativeFillResult(new CoreUserState(user.productLine(), user.userId(),
                Math.incrementExact(user.revision()), balances, reservations, positions, user.positionMode()), treasury);
    }

    private static CashResult applyCash(AssetBalance balance, long delta) {
        if (delta >= 0) {
            return new CashResult(delta == 0 ? balance : balance.credit(delta), delta);
        }
        long debit = Math.min(balance.availableUnits(), Math.negateExact(delta));
        return new CashResult(debit == 0 ? balance : balance.adjustAvailable(Math.negateExact(debit)),
                Math.negateExact(debit));
    }

    private static long proportional(long units, long part, long total) {
        return part == total ? units : Math.multiplyExact(units, part) / total;
    }

    private record DerivativeFillResult(CoreUserState user, CoreTreasuryState treasury) {
    }

    private record SpotFillResult(CoreUserState user, CoreTreasuryState treasury) {
    }

    private record CashResult(AssetBalance balance, long appliedDelta) {
    }

    private static CoreUserState releaseTerminalReservation(CoreUserState user, long orderId) {
        OrderReservation reservation = requireReservation(user, orderId);
        long releaseUnits = reservation.remainingUnits();
        if (releaseUnits == 0) {
            return user;
        }
        Map<String, AssetBalance> balances = StateMapSupport.delta(user.balances());
        balances.put(reservation.asset(), requireBalance(user, reservation.asset()).release(releaseUnits));
        Map<Long, OrderReservation> reservations = StateMapSupport.delta(user.reservations());
        reservations.put(orderId, reservation.releaseAll());
        return new CoreUserState(user.productLine(), user.userId(), Math.incrementExact(user.revision()),
                balances, reservations, user.positions(), user.positionMode());
    }

    private static OrderReservation requireReservation(CoreUserState user, long orderId) {
        OrderReservation reservation = user.reservations().get(orderId);
        if (reservation == null) {
            throw new IllegalStateException("open order reservation is missing orderId=" + orderId);
        }
        return reservation;
    }

    private static AssetBalance requireBalance(CoreUserState user, String asset) {
        AssetBalance balance = user.balances().get(asset);
        if (balance == null) {
            throw new IllegalStateException("reservation balance is missing asset=" + asset);
        }
        return balance;
    }

    private static TradingCoreState replaceUser(
            TradingCoreState state,
            CoreUserState user,
            Map<Long, CoreOrderState> orders,
            CoreBookState bookState) {
        return replaceUser(state, user, orders, bookState, StateMapSupport.delta(state.clientOrderIndex()));
    }

    private static TradingCoreState replaceUser(
            TradingCoreState state,
            CoreUserState user,
            Map<Long, CoreOrderState> orders,
            CoreBookState bookState,
            Map<ClientOrderKey, Long> clientOrderIndex) {
        Map<Long, CoreUserState> users = StateMapSupport.delta(state.users());
        users.put(user.userId(), user);
        Map<Long, CoreOrderState> nextOrders = StateMapSupport.isDelta(orders)
                ? orders : StateMapSupport.delta(orders);
        Map<ClientOrderKey, Long> nextClientOrderIndex = clientOrderIndex == null
                ? StateMapSupport.delta(state.clientOrderIndex()) : clientOrderIndex;
        if (clientOrderIndex != null) {
            return new TradingCoreState(state.productLine(), Math.incrementExact(state.revision()), users, nextOrders,
                    bookState, state.instruments(), state.riskState(), state.treasuryState(),
                    StateMapSupport.delta(state.leverages()), StateMapSupport.delta(state.algoOrders()),
                    StateMapSupport.delta(state.cancelAllAfterTimers()), nextClientOrderIndex,
                    StateMapSupport.delta(state.triggerOrders()));
        }
        return new TradingCoreState(state.productLine(), Math.incrementExact(state.revision()), users, nextOrders,
                bookState, state.instruments(), state.riskState(), state.treasuryState(),
                StateMapSupport.delta(state.leverages()), StateMapSupport.delta(state.algoOrders()),
                StateMapSupport.delta(state.cancelAllAfterTimers()), nextClientOrderIndex,
                StateMapSupport.delta(state.triggerOrders()));
    }

    private static void validateReservationRule(TradingCoreState state, PlaceOrderCommand command) {
        String reservationAsset = AssetBalance.normalizeAsset(command.reservationAsset());
        if (state.productLine().isDerivative()) {
            if (command.reservationKind() != ReservationKind.DERIVATIVE_MARGIN) {
                throw new CoreStateRejectedException("INVALID_RESERVATION_KIND",
                        "derivative orders require DERIVATIVE_MARGIN");
            }
            String settleAsset = AssetBalance.normalizeAsset(command.settleAsset());
            if (!reservationAsset.equals(settleAsset)) {
                throw new CoreStateRejectedException("INVALID_DERIVATIVE_RESERVATION_ASSET",
                        "derivative orders reserve the instrument settle asset");
            }
            return;
        }
        if (command.reservationKind() != ReservationKind.SPOT_ASSET) {
            throw new CoreStateRejectedException("INVALID_RESERVATION_KIND",
                    "spot orders require SPOT_ASSET");
        }
        String expectedAsset = AssetBalance.normalizeAsset(command.side() == CoreOrderSide.BUY
                ? command.quoteAsset() : command.baseAsset());
        if (!reservationAsset.equals(expectedAsset)) {
            throw new CoreStateRejectedException("INVALID_SPOT_RESERVATION_ASSET",
                    "spot buy reserves quote asset and spot sell reserves base asset");
        }
    }

    private static void validateInstrumentOrder(CoreInstrumentState instrument, PlaceOrderCommand command) {
        if (!instrument.baseAsset().equals(AssetBalance.normalizeAsset(command.baseAsset()))
                || !instrument.quoteAsset().equals(AssetBalance.normalizeAsset(command.quoteAsset()))
                || !instrument.settleAsset().equals(AssetBalance.normalizeAsset(command.settleAsset()))) {
            throw new CoreStateRejectedException("INSTRUMENT_ORDER_MISMATCH",
                    "order assets do not match instrument state");
        }
        if (command.matchingPriceTicks() <= 0) {
            throw new CoreStateRejectedException("INVALID_ORDER_PRICE", "matching price must be positive");
        }
    }

    private static long requiredReservationUnits(
            TradingCoreState state,
            CoreInstrumentState instrument,
            CoreUserState user,
            PlaceOrderCommand command) {
        if (instrument.contractType() == com.surprising.instrument.api.model.ContractType.SPOT) {
            if (command.side() == CoreOrderSide.SELL) return command.quantitySteps();
            long notional = Math.multiplyExact(command.matchingPriceTicks(), command.quantitySteps());
            long fee = CoreContractMath.feeDeltaUnits(instrument, command.matchingPriceTicks(),
                    command.quantitySteps(), command.takerFeeRatePpm());
            return Math.addExact(notional, Math.max(0, Math.negateExact(fee)));
        }
        CorePositionState position = user.positions().get(positionKey(instrument.symbol(), command.positionSide()));
        long currentQuantity = position == null ? 0 : position.signedQuantitySteps();
        long signedOrder = command.side() == CoreOrderSide.BUY
                ? command.quantitySteps() : Math.negateExact(command.quantitySteps());
        long closeSteps = currentQuantity != 0 && Long.signum(currentQuantity) != Long.signum(signedOrder)
                ? Math.min(Math.absExact(currentQuantity), command.quantitySteps()) : 0;
        long leverage = state.leverages().getOrDefault(
                new CoreLeverageKey(user.userId(), instrument.symbol(), command.marginMode()),
                instrument.maxLeveragePpm());
        long projectedSteps = projectedPositionSignedSteps(state, instrument, user, command, command.quantitySteps());
        long pendingSteps = projectedPositionSignedSteps(state, instrument, user, command, 0);
        long currentMargin = position == null ? 0 : position.positionMarginUnits();
        long releasedMargin = position == null || closeSteps == 0 ? 0
                : proportional(currentMargin, closeSteps, Math.absExact(currentQuantity));
        long pendingMargin = positionMarginRequirement(instrument, pendingSteps, command.matchingPriceTicks(), leverage);
        long pendingOrderSteps = Math.subtractExact(pendingSteps, currentQuantity);
        long pendingAdditionalMargin = pendingOrderSteps == 0 ? 0
                : Math.max(0, Math.subtractExact(pendingMargin, currentMargin));
        long projectedMargin = positionMarginRequirement(instrument, projectedSteps, command.matchingPriceTicks(), leverage);
        long margin = Math.max(0, Math.subtractExact(projectedMargin,
                Math.addExact(Math.subtractExact(currentMargin, releasedMargin), pendingAdditionalMargin)));
        long premium = instrument.contractType().isOption() && command.side() == CoreOrderSide.BUY
                ? CoreContractMath.optionPremiumUnits(instrument, command.matchingPriceTicks(), command.quantitySteps()) : 0;
        long fee = CoreContractMath.feeDeltaUnits(instrument, command.matchingPriceTicks(),
                command.quantitySteps(), command.takerFeeRatePpm());
        return Math.max(1, Math.addExact(Math.addExact(margin, premium), Math.max(0, Math.negateExact(fee))));
    }

    private static long positionMarginRequirement(
            CoreInstrumentState instrument,
            long signedQuantitySteps,
            long priceTicks,
            long leveragePpm) {
        if (signedQuantitySteps == 0) return 0;
        long quantitySteps = Math.absExact(signedQuantitySteps);
        long notional = CoreContractMath.notionalUnits(instrument, quantitySteps, priceTicks);
        com.surprising.aeron.protocol.CoreRiskLimitBracket bracket = CoreContractMath.maintenanceRiskBracket(
                instrument, notional);
        long effectiveRate = Math.max(Math.max(instrument.initialMarginRatePpm(), bracket.initialMarginRatePpm()),
                initialMarginRateFromLeverage(leveragePpm));
        CoreOrderSide side = signedQuantitySteps > 0 ? CoreOrderSide.BUY : CoreOrderSide.SELL;
        return CoreContractMath.openingMarginUnits(instrument, side, priceTicks, quantitySteps, effectiveRate);
    }

    private static void validateDerivativeRiskLimits(
            TradingCoreState state,
            CoreInstrumentState instrument,
            CoreUserState user,
            PlaceOrderCommand command,
            long indexedOpenInterestSteps) {
        if (!state.productLine().isDerivative() || command.reduceOnly()) return;
        long projectedNotional = projectedPositionNotionalUnits(state, instrument, user, command);
        if (projectedNotional > instrument.maxPositionNotionalUnits()) {
            throw new CoreStateRejectedException("POSITION_NOTIONAL_LIMIT_EXCEEDED",
                    "projected position exceeds instrument notional limit");
        }
        long openInterestSteps = indexedOpenInterestSteps;
        long openInterestNotional = openInterestSteps == 0 ? 0
                : CoreContractMath.notionalUnits(instrument, openInterestSteps, command.matchingPriceTicks());
        long scaledLimit = java.math.BigInteger.valueOf(openInterestNotional)
                .multiply(java.math.BigInteger.valueOf(instrument.userOpenInterestLimitRatePpm()))
                .divide(java.math.BigInteger.valueOf(PPM))
                .max(java.math.BigInteger.valueOf(instrument.userOpenInterestLimitFloorUnits()))
                .min(java.math.BigInteger.valueOf(instrument.maxPositionNotionalUnits())).longValueExact();
        if (projectedNotional > scaledLimit) {
            throw new CoreStateRejectedException("OPEN_INTEREST_LIMIT_EXCEEDED",
                    "projected position exceeds dynamic open interest limit");
        }
        com.surprising.aeron.protocol.CoreRiskLimitBracket bracket = riskBracket(instrument, projectedNotional);
        if (projectedNotional > bracket.notionalCapUnits()) {
            throw new CoreStateRejectedException("RISK_BRACKET_EXCEEDED",
                    "projected position exceeds risk bracket cap");
        }
        long leverage = state.leverages().getOrDefault(
                new CoreLeverageKey(user.userId(), instrument.symbol(), command.marginMode()),
                instrument.maxLeveragePpm());
        if (leverage > bracket.maxLeveragePpm()) {
            throw new CoreStateRejectedException("LEVERAGE_EXCEEDS_RISK_BRACKET",
                    "configured leverage exceeds projected position risk bracket");
        }
        if (initialMarginRateFromLeverage(leverage) < bracket.initialMarginRatePpm()) {
            throw new CoreStateRejectedException("LEVERAGE_EXCEEDS_RISK_BRACKET",
                    "configured leverage margin rate is below projected position risk bracket");
        }
    }

    private static long projectedPositionNotionalUnits(
            TradingCoreState state,
            CoreInstrumentState instrument,
            CoreUserState user,
            PlaceOrderCommand command) {
        return CoreContractMath.notionalUnits(instrument,
                projectedPositionSteps(state, instrument, user, command, command.quantitySteps()),
                command.matchingPriceTicks());
    }

    private static long projectedPositionSteps(
            TradingCoreState state,
            CoreInstrumentState instrument,
            CoreUserState user,
            PlaceOrderCommand command,
            long additionalQuantitySteps) {
        return Math.absExact(projectedPositionSignedSteps(state, instrument, user, command, additionalQuantitySteps));
    }

    private static long projectedPositionSignedSteps(
            TradingCoreState state,
            CoreInstrumentState instrument,
            CoreUserState user,
            PlaceOrderCommand command,
            long additionalQuantitySteps) {
        CorePositionState position = user.positions().get(positionKey(instrument.symbol(), command.positionSide()));
        long current = position == null ? 0 : position.signedQuantitySteps();
        long pendingSameSide = userOrders(state, user).stream()
                .filter(order -> order.status() == CoreOrderStatus.OPEN && !order.reduceOnly()
                        && order.symbol().equals(instrument.symbol()) && order.positionSide() == command.positionSide()
                        && order.side() == command.side())
                .mapToLong(CoreOrderState::remainingQuantitySteps).reduce(0L, Math::addExact);
        long totalOrderSteps = Math.addExact(pendingSameSide, additionalQuantitySteps);
        long signedOrderSteps = command.side() == CoreOrderSide.BUY
                ? totalOrderSteps : Math.negateExact(totalOrderSteps);
        return Math.addExact(current, signedOrderSteps);
    }

    private static long symbolOpenInterestSteps(TradingCoreState state, String symbol) {
        long longSteps = 0;
        long shortSteps = 0;
        for (CoreUserState user : state.users().values()) {
            for (CorePositionState position : user.positions().values()) {
                if (!position.symbol().equals(symbol)) continue;
                if (position.signedQuantitySteps() > 0) {
                    longSteps = Math.addExact(longSteps, position.signedQuantitySteps());
                } else if (position.signedQuantitySteps() < 0) {
                    shortSteps = Math.addExact(shortSteps, Math.absExact(position.signedQuantitySteps()));
                }
            }
        }
        return Math.max(longSteps, shortSteps);
    }

    private static com.surprising.aeron.protocol.CoreRiskLimitBracket riskBracket(
            CoreInstrumentState instrument, long projectedNotional) {
        return CoreContractMath.riskBracket(instrument, projectedNotional);
    }

    private static long initialMarginRateFromLeverage(long leveragePpm) {
        if (leveragePpm < PPM) throw new IllegalArgumentException("leverage must be at least 1x");
        java.math.BigInteger numerator = java.math.BigInteger.valueOf(PPM).multiply(java.math.BigInteger.valueOf(PPM));
        java.math.BigInteger denominator = java.math.BigInteger.valueOf(leveragePpm);
        java.math.BigInteger[] quotient = numerator.divideAndRemainder(denominator);
        return (quotient[1].signum() == 0 ? quotient[0] : quotient[0].add(java.math.BigInteger.ONE)).longValueExact();
    }

    private static void validateReduceOnlyCapacity(
            TradingCoreState state,
            CoreUserState user,
            PlaceOrderCommand command) {
        if (!command.reduceOnly()) {
            return;
        }
        if (!state.productLine().isDerivative()) {
            throw new CoreStateRejectedException("REDUCE_ONLY_UNSUPPORTED", "spot orders cannot be reduce-only");
        }
        CorePositionState position = user.positions().get(positionKey(command.symbol(), command.positionSide()));
        if (position == null || position.signedQuantitySteps() == 0
                || (position.signedQuantitySteps() > 0) == (command.side() == CoreOrderSide.BUY)) {
            throw new CoreStateRejectedException("REDUCE_ONLY_REQUIRES_POSITION_STATE",
                    "reduce-only side must close an existing position");
        }
        long alreadyOpen = userOrders(state, user).stream()
                .filter(order -> order.reduceOnly() && order.status() == CoreOrderStatus.OPEN
                        && order.symbol().equals(position.symbol())
                        && order.side() == command.side())
                .mapToLong(CoreOrderState::remainingQuantitySteps)
                .reduce(0L, Math::addExact);
        long capacity = Math.subtractExact(Math.absExact(position.signedQuantitySteps()), alreadyOpen);
        if (command.quantitySteps() > capacity) {
            throw new CoreStateRejectedException("REDUCE_ONLY_CAPACITY_EXCEEDED",
                    "reduce-only open quantity exceeds position capacity");
        }
    }

    private static void validatePositionIdentity(TradingCoreState state, CoreUserState user,
                                                 PlaceOrderCommand command) {
        if (user.positionMode() == CorePositionMode.ONE_WAY && command.positionSide().hedgeSide()
                || user.positionMode() == CorePositionMode.HEDGE && !command.positionSide().hedgeSide()) {
            throw new CoreStateRejectedException("POSITION_MODE_MISMATCH",
                    "order position side does not match user position mode");
        }
        if (command.marginMode() == CoreMarginMode.ISOLATED
                && command.reservationKind() == ReservationKind.SPOT_ASSET) {
            throw new CoreStateRejectedException("POSITION_MARGIN_ADJUSTMENT_INVALID",
                    "spot order cannot use isolated margin");
        }
        CorePositionState position = user.positions().get(positionKey(command.symbol(), command.positionSide()));
        boolean positionConflict = position != null && position.signedQuantitySteps() != 0
                && position.marginMode() != command.marginMode();
        boolean orderConflict = userOrders(state, user).stream().anyMatch(order -> order.status() == CoreOrderStatus.OPEN
                && order.symbol().equalsIgnoreCase(command.symbol())
                && order.positionSide() == command.positionSide() && order.marginMode() != command.marginMode());
        if (positionConflict || orderConflict) {
            throw new CoreStateRejectedException("POSITION_MARGIN_ADJUSTMENT_INVALID",
                    "margin mode switch requires closing positions and open orders first");
        }
        if (command.positionSide() == com.surprising.aeron.protocol.CorePositionSide.LONG
                && command.reduceOnly() == (command.side() == CoreOrderSide.BUY)
                || command.positionSide() == com.surprising.aeron.protocol.CorePositionSide.SHORT
                && command.reduceOnly() == (command.side() == CoreOrderSide.SELL)) {
            throw new CoreStateRejectedException("POSITION_MODE_MISMATCH",
                    "hedge position side and order direction are inconsistent");
        }
    }

    private static void requireUserId(long userId) {
        if (userId <= 0) {
            throw new CoreStateRejectedException("INVALID_USER_ID", "userId must be positive");
        }
    }

    private static List<CoreOrderState> userOrders(TradingCoreState state, CoreUserState user) {
        return user.reservations().keySet().stream()
                .map(state.orders()::get)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static Iterable<CoreUserState> usersAfter(Map<Long, CoreUserState> users, long lastUserId) {
        if (users instanceof NavigableMap<?, ?> navigable) {
            return ((NavigableMap<Long, CoreUserState>) navigable).tailMap(lastUserId, false).values();
        }
        return users.values().stream().filter(user -> user.userId() > lastUserId).toList();
    }

    private static Iterable<CoreUserState> usersAfter(TradingCoreState state, java.util.Set<Long> userIds,
                                                      long lastUserId) {
        return userIds.stream()
                .filter(userId -> userId > lastUserId)
                .map(state::user)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private static String positionKey(String symbol, com.surprising.aeron.protocol.CorePositionSide side) {
        String normalized = OrderReservation.normalizeSymbol(symbol);
        return side.hedgeSide() ? normalized + ':' + side.name() : normalized;
    }

    private static List<CorePositionState> positionsForSymbol(CoreUserState user, String symbol) {
        String normalized = OrderReservation.normalizeSymbol(symbol);
        return user.positions().values().stream()
                .filter(position -> position.symbol().equals(normalized) && position.signedQuantitySteps() != 0)
                .toList();
    }
}
