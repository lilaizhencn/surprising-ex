package com.surprising.aeron.service.state;
import com.surprising.aeron.service.state.instrument.CoreInstrumentState;

import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CorePositionMode;
import com.surprising.aeron.protocol.CoreTriggerOrderStateView;
import com.surprising.aeron.protocol.CoreTriggerOrderStatus;
import com.surprising.aeron.protocol.CoreTriggerOrderType;
import com.surprising.aeron.service.exception.CoreStateRejectedException;
import com.surprising.aeron.service.state.index.TriggerOrderIndex;
import com.surprising.aeron.service.state.model.CoreOrderState;
import com.surprising.aeron.service.state.model.CoreOrderStatus;
import com.surprising.aeron.service.state.model.CorePositionState;
import com.surprising.aeron.service.state.model.CoreTriggerOrderState;

import java.util.Map;

import static com.surprising.aeron.service.state.ReducerSettlementSupport.positionKey;
import static com.surprising.aeron.service.state.ReducerSettlementSupport.userOrders;

/** Owns immutable trigger-order lifecycle changes in the core state. */
final class TriggerOrderStateTransitions {

    private TriggerOrderStateTransitions() {
    }

    static TradingCoreState upsertTriggerOrder(
            TradingCoreState state, long userId, CoreTriggerOrderStateView view) {
        return upsertTriggerOrder(state, userId, view, null);
    }

    static TradingCoreState upsertTriggerOrder(
            TradingCoreState state, long userId, CoreTriggerOrderStateView view,
            TriggerOrderIndex triggerOrderIndex) {
        requireUserId(userId);
        if (view.userId() != userId || view.productLine() != state.productLine()) {
            throw new CoreStateRejectedException("TRIGGER_ORDER_OWNER_MISMATCH", "trigger order owner mismatch");
        }
        if (view.clientTriggerOrderId().isBlank()) {
            throw new CoreStateRejectedException("INVALID_COMMAND", "clientTriggerOrderId is required");
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
        if (trigger.instrumentChangeId() == 0) {
            trigger = trigger.withExecutionSnapshot(instrument.changeId(), instrument.makerFeeRatePpm(),
                    instrument.takerFeeRatePpm());
        } else if (trigger.instrumentChangeId() != instrument.changeId()) {
            throw new CoreStateRejectedException("STALE_INSTRUMENT_CHANGE_ID",
                    "trigger order instrument version is stale");
        }
        triggers.put(view.triggerOrderId(), trigger);
        return withTriggers(state, triggers);
    }

    private static void validateTriggerPlacement(
            TradingCoreState state, CoreTriggerOrderStateView view, TriggerOrderIndex triggerOrderIndex) {
        CoreUserState user = state.user(view.userId());
        if (user == null) {
            throw new CoreStateRejectedException("USER_NOT_FOUND", "user does not exist");
        }
        if (!state.productLine().isDerivative()) return;
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

    static TradingCoreState cancelTriggerOrder(TradingCoreState state, long userId, long triggerOrderId) {
        requireUserId(userId);
        CoreTriggerOrderState current = requireTrigger(state, triggerOrderId);
        if (current.userId() != userId) {
            throw new CoreStateRejectedException("TRIGGER_ORDER_OWNER_MISMATCH", "trigger order owner mismatch");
        }
        if (!current.status().open()) return state;
        return updateTrigger(state, current, CoreTriggerOrderStatus.CANCELED, 0, current.triggerSequence(),
                current.triggeredPriceTicks(), current.rejectReason(), current.updatedAtEpochMillis());
    }

    static TradingCoreState claimTriggerOrder(
            TradingCoreState state, long triggerOrderId, long triggerSequence,
            long triggeredPriceTicks, long triggeredAtEpochMillis) {
        CoreTriggerOrderState current = requireTrigger(state, triggerOrderId);
        if (current.status() != CoreTriggerOrderStatus.PENDING) return state;
        return updateTrigger(state, current, CoreTriggerOrderStatus.TRIGGERING, 0, triggerSequence,
                triggeredPriceTicks, current.rejectReason(), triggeredAtEpochMillis);
    }

    static TradingCoreState completeTriggerOrder(
            TradingCoreState state, long triggerOrderId, boolean success, long placedOrderId,
            String rejectReason, long completedAtEpochMillis) {
        CoreTriggerOrderState current = requireTrigger(state, triggerOrderId);
        if (current.status() != CoreTriggerOrderStatus.TRIGGERING) return state;
        return updateTrigger(state, current, success ? CoreTriggerOrderStatus.TRIGGERED
                        : CoreTriggerOrderStatus.TRIGGER_FAILED, placedOrderId, current.triggerSequence(),
                current.triggeredPriceTicks(), rejectReason, completedAtEpochMillis);
    }

    static TradingCoreState updateTriggerTrailing(
            TradingCoreState state, long triggerOrderId, long highestPriceTicks,
            long lowestPriceTicks, long activatedAtEpochMillis) {
        CoreTriggerOrderState current = requireTrigger(state, triggerOrderId);
        if (!current.status().open() || current.triggerType() != CoreTriggerOrderType.TRAILING_STOP) return state;
        Map<Long, CoreTriggerOrderState> triggers = StateMapSupport.delta(state.triggerOrders());
        triggers.put(triggerOrderId, new CoreTriggerOrderState(current.triggerOrderId(), current.productLine(),
                current.userId(), current.clientTriggerOrderId(), current.ocoGroupId(), current.symbol(), current.side(),
                current.triggerType(), current.triggerCondition(), current.triggerPriceTicks(), current.activationPriceTicks(),
                current.callbackRatePpm(), highestPriceTicks, lowestPriceTicks, activatedAtEpochMillis,
                current.orderType(), current.timeInForce(), current.priceTicks(), current.quantitySteps(),
                current.marginMode(), current.positionSide(), current.status(), current.placedOrderId(),
                current.triggerSequence(), current.triggeredPriceTicks(), current.rejectReason(), current.traceId(),
                current.expiresAtEpochMillis(), current.triggeredAtEpochMillis(), current.createdAtEpochMillis(),
                Math.max(current.updatedAtEpochMillis(), activatedAtEpochMillis), Math.incrementExact(current.revision()),
                current.instrumentChangeId(), current.makerFeeRatePpm(), current.takerFeeRatePpm()));
        return withTriggers(state, triggers);
    }

    static TradingCoreState expireTriggerOrder(
            TradingCoreState state, long triggerOrderId, long expiredAtEpochMillis) {
        CoreTriggerOrderState current = requireTrigger(state, triggerOrderId);
        if (current.status() != CoreTriggerOrderStatus.PENDING
                || current.expiresAtEpochMillis() == 0
                || current.expiresAtEpochMillis() > expiredAtEpochMillis) return state;
        return updateTrigger(state, current, CoreTriggerOrderStatus.EXPIRED, 0, current.triggerSequence(),
                current.triggeredPriceTicks(), current.rejectReason(), expiredAtEpochMillis);
    }

    static TradingCoreState retryTriggerOrder(
            TradingCoreState state, long triggerOrderId, long staleBeforeEpochMillis, long retryAtEpochMillis) {
        CoreTriggerOrderState current = requireTrigger(state, triggerOrderId);
        if (current.status() != CoreTriggerOrderStatus.TRIGGERING
                || current.updatedAtEpochMillis() > staleBeforeEpochMillis) return state;
        return updateTrigger(state, current, CoreTriggerOrderStatus.PENDING, 0, 0, 0,
                current.rejectReason(), retryAtEpochMillis);
    }

    private static TradingCoreState updateTrigger(
            TradingCoreState state, CoreTriggerOrderState current, CoreTriggerOrderStatus status,
            long placedOrderId, long triggerSequence, long triggeredPriceTicks,
            String rejectReason, long updatedAt) {
        Map<Long, CoreTriggerOrderState> triggers = StateMapSupport.delta(state.triggerOrders());
        triggers.put(current.triggerOrderId(), new CoreTriggerOrderState(current.triggerOrderId(), current.productLine(),
                current.userId(), current.clientTriggerOrderId(), current.ocoGroupId(), current.symbol(), current.side(),
                current.triggerType(), current.triggerCondition(), current.triggerPriceTicks(), current.activationPriceTicks(),
                current.callbackRatePpm(), current.highestPriceTicks(), current.lowestPriceTicks(),
                current.activatedAtEpochMillis(), current.orderType(), current.timeInForce(), current.priceTicks(),
                current.quantitySteps(), current.marginMode(), current.positionSide(), status, placedOrderId,
                triggerSequence, triggeredPriceTicks, rejectReason, current.traceId(), current.expiresAtEpochMillis(),
                status == CoreTriggerOrderStatus.TRIGGERED || status == CoreTriggerOrderStatus.TRIGGER_FAILED
                        ? updatedAt : current.triggeredAtEpochMillis(), current.createdAtEpochMillis(), updatedAt,
                Math.incrementExact(current.revision()), current.instrumentChangeId(), current.makerFeeRatePpm(),
                current.takerFeeRatePpm()));
        return withTriggers(state, triggers);
    }

    private static CoreTriggerOrderState requireTrigger(TradingCoreState state, long triggerOrderId) {
        CoreTriggerOrderState current = state.triggerOrders().get(triggerOrderId);
        if (current == null) {
            throw new CoreStateRejectedException("TRIGGER_ORDER_NOT_FOUND", "trigger order not found");
        }
        return current;
    }

    private static TradingCoreState withTriggers(
            TradingCoreState state, Map<Long, CoreTriggerOrderState> triggers) {
        return new TradingCoreState(state.productLine(), Math.incrementExact(state.revision()),
                state.users(), state.orders(), state.instruments(), state.riskState(), state.treasuryState(),
                state.leverages(), state.algoOrders(), state.cancelAllAfterTimers(), state.clientOrderIndex(), triggers);
    }

    private static void requireUserId(long userId) {
        if (userId <= 0) {
            throw new CoreStateRejectedException("INVALID_USER_ID", "userId must be positive");
        }
    }
}
