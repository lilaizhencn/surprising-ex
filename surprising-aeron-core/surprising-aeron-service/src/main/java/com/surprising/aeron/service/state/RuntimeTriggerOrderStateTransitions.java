package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CorePositionMode;
import com.surprising.aeron.protocol.CoreTriggerOrderStateView;
import com.surprising.aeron.protocol.CoreTriggerOrderStatus;
import com.surprising.aeron.protocol.CoreTriggerOrderType;
import com.surprising.aeron.service.exception.CoreStateRejectedException;
import com.surprising.aeron.service.state.model.CoreTriggerOrderState;
import com.surprising.product.api.ProductLine;

/** Owns runtime trigger-order lifecycle and derivative position-capacity checks. */
final class RuntimeTriggerOrderStateTransitions {

    private RuntimeTriggerOrderStateTransitions() {
    }

    static void upsert(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                       long userId, CoreTriggerOrderStateView view) {
        if (runtime == null || identities == null || view == null || userId <= 0) {
            throw new IllegalArgumentException("invalid runtime trigger order update");
        }
        int symbolId = identities.symbolId(view.symbol());
        long positionKey = positionKey(identities, runtime.productLine(), userId, view);
        boolean instrumentSettled = runtime.treasury().lifecycleSettlement(symbolId) != 0;
        upsert(runtime, userId, view, symbolId, positionKey, instrumentSettled);
    }

    static void upsert(TradingRuntimeState runtime, long userId,
                       CoreTriggerOrderStateView view, int symbolId,
                       long positionKey, boolean instrumentSettled) {
        if (runtime == null || view == null || userId <= 0 || symbolId < 0 || positionKey < 0) {
            throw new IllegalArgumentException("invalid prepared runtime trigger order update");
        }
        runtime.assertOwner();
        if (view.userId() != userId || view.productLine() != runtime.productLine()) {
            throw new CoreStateRejectedException("TRIGGER_ORDER_OWNER_MISMATCH", "trigger order owner mismatch");
        }
        if (view.clientTriggerOrderId().isBlank()) {
            throw new CoreStateRejectedException("INVALID_COMMAND", "clientTriggerOrderId is required");
        }
        CoreInstrumentState instrument = runtime.instrument(view.symbol());
        if (instrument == null) {
            throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "trigger order instrument does not exist");
        }
        if (instrumentSettled) {
            throw new CoreStateRejectedException("INSTRUMENT_SETTLED", "instrument is already settled");
        }
        if (runtime.triggerOrder(view.triggerOrderId()) != null) {
            throw new CoreStateRejectedException("DUPLICATE_TRIGGER_ORDER_ID", "trigger order already exists");
        }
        if (runtime.hasTriggerClient(userId, view.clientTriggerOrderId())) {
            throw new CoreStateRejectedException("DUPLICATE_CLIENT_TRIGGER_ORDER_ID",
                    "clientTriggerOrderId already exists");
        }
        validatePlacement(runtime, userId, symbolId, positionKey, view);
        CoreTriggerOrderState trigger = CoreTriggerOrderState.from(view);
        if (trigger.instrumentChangeId() == 0) {
            trigger = trigger.withExecutionSnapshot(instrument.changeId(), instrument.makerFeeRatePpm(),
                    instrument.takerFeeRatePpm());
        } else if (trigger.instrumentChangeId() != instrument.changeId()) {
            throw new CoreStateRejectedException("STALE_INSTRUMENT_CHANGE_ID",
                    "trigger order instrument version is stale");
        }
        runtime.putTriggerOrder(trigger);
        runtime.incrementCommandRevision();
    }

    static boolean cancel(TradingRuntimeState runtime, long userId, long triggerOrderId) {
        requireInput(runtime, userId, triggerOrderId);
        CoreTriggerOrderState current = require(runtime, triggerOrderId);
        if (current.userId() != userId) {
            throw new CoreStateRejectedException("TRIGGER_ORDER_OWNER_MISMATCH", "trigger order owner mismatch");
        }
        if (!current.status().open()) return false;
        update(runtime, current, CoreTriggerOrderStatus.CANCELED, 0, current.triggerSequence(),
                current.triggeredPriceTicks(), current.rejectReason(), current.updatedAtEpochMillis());
        return true;
    }

    /** Commit event owns version publication; this helper mutates only its scoped account Lane. */
    static boolean cancelPendingForCommit(TradingRuntimeState runtime, long triggerId) {
        CoreTriggerOrderState current = require(runtime, triggerId);
        if (current.status() != CoreTriggerOrderStatus.PENDING) return false;
        runtime.putTriggerOrder(preparePendingCancellation(current));
        return true;
    }

    static CoreTriggerOrderState preparePendingCancellation(CoreTriggerOrderState current) {
        if (current == null || current.status() != CoreTriggerOrderStatus.PENDING)
            throw new IllegalStateException("closing trigger must be pending");
        return transition(current, CoreTriggerOrderStatus.CANCELED, 0, current.triggerSequence(),
                current.triggeredPriceTicks(), current.rejectReason(), current.updatedAtEpochMillis());
    }

    static boolean claim(TradingRuntimeState runtime, long triggerOrderId, long triggerSequence,
                         long triggeredPriceTicks, long triggeredAtEpochMillis) {
        CoreTriggerOrderState current = require(runtime, triggerOrderId);
        if (current.status() != CoreTriggerOrderStatus.PENDING) return false;
        update(runtime, current, CoreTriggerOrderStatus.TRIGGERING, 0, triggerSequence,
                triggeredPriceTicks, current.rejectReason(), triggeredAtEpochMillis);
        return true;
    }

    static boolean complete(TradingRuntimeState runtime, long triggerOrderId, boolean success,
                            long placedOrderId, String rejectReason, long completedAtEpochMillis) {
        CoreTriggerOrderState current = require(runtime, triggerOrderId);
        if (current.status() != CoreTriggerOrderStatus.TRIGGERING) return false;
        update(runtime, current, success ? CoreTriggerOrderStatus.TRIGGERED
                        : CoreTriggerOrderStatus.TRIGGER_FAILED, placedOrderId, current.triggerSequence(),
                current.triggeredPriceTicks(), rejectReason, completedAtEpochMillis);
        return true;
    }

    static boolean updateTrailing(TradingRuntimeState runtime, long triggerOrderId,
                                  long highestPriceTicks, long lowestPriceTicks,
                                  long activatedAtEpochMillis) {
        CoreTriggerOrderState current = require(runtime, triggerOrderId);
        if (!current.status().open() || current.triggerType() != CoreTriggerOrderType.TRAILING_STOP) return false;
        runtime.putTriggerOrder(new CoreTriggerOrderState(current.triggerOrderId(), current.productLine(),
                current.userId(), current.clientTriggerOrderId(), current.ocoGroupId(), current.symbol(), current.side(),
                current.triggerType(), current.triggerCondition(), current.triggerPriceTicks(),
                current.activationPriceTicks(), current.callbackRatePpm(), highestPriceTicks, lowestPriceTicks,
                activatedAtEpochMillis, current.orderType(), current.timeInForce(), current.priceTicks(),
                current.quantitySteps(), current.marginMode(), current.positionSide(), current.status(),
                current.placedOrderId(), current.triggerSequence(), current.triggeredPriceTicks(), current.rejectReason(),
                current.traceId(), current.expiresAtEpochMillis(), current.triggeredAtEpochMillis(),
                current.createdAtEpochMillis(), Math.max(current.updatedAtEpochMillis(), activatedAtEpochMillis),
                Math.incrementExact(current.revision()), current.instrumentChangeId(), current.makerFeeRatePpm(),
                current.takerFeeRatePpm()));
        runtime.incrementCommandRevision();
        return true;
    }

    static boolean expire(TradingRuntimeState runtime, long triggerOrderId, long expiredAtEpochMillis) {
        CoreTriggerOrderState current = require(runtime, triggerOrderId);
        if (current.status() != CoreTriggerOrderStatus.PENDING || current.expiresAtEpochMillis() == 0
                || current.expiresAtEpochMillis() > expiredAtEpochMillis) return false;
        update(runtime, current, CoreTriggerOrderStatus.EXPIRED, 0, current.triggerSequence(),
                current.triggeredPriceTicks(), current.rejectReason(), expiredAtEpochMillis);
        return true;
    }

    static boolean retry(TradingRuntimeState runtime, long triggerOrderId,
                         long staleBeforeEpochMillis, long retryAtEpochMillis) {
        CoreTriggerOrderState current = require(runtime, triggerOrderId);
        if (current.status() != CoreTriggerOrderStatus.TRIGGERING
                || current.updatedAtEpochMillis() > staleBeforeEpochMillis) return false;
        update(runtime, current, CoreTriggerOrderStatus.PENDING, 0, 0, 0,
                current.rejectReason(), retryAtEpochMillis);
        return true;
    }

    static long positionKey(RuntimeIdentityRegistry identities, ProductLine productLine,
                            long userId, CoreTriggerOrderStateView view) {
        if (!productLine.isDerivative()) return 0;
        String positionIdentity = view.positionSide().hedgeSide()
                ? OrderReservation.normalizeSymbol(view.symbol()) + ':' + view.positionSide().name()
                : OrderReservation.normalizeSymbol(view.symbol());
        return identities.positionKey(userId, positionIdentity);
    }

    static CoreTriggerOrderState prepareMatchedCompletion(
            CoreTriggerOrderState current, long placedOrderId, long updatedAt) {
        if (current == null || current.status() != CoreTriggerOrderStatus.TRIGGERING || placedOrderId <= 0)
            throw new IllegalStateException("invalid matched trigger completion");
        return transition(current, CoreTriggerOrderStatus.TRIGGERED, placedOrderId,
                current.triggerSequence(), current.triggeredPriceTicks(), "", updatedAt);
    }

    static CoreTriggerOrderState prepareRejectedCompletion(
            CoreTriggerOrderState current, String reason, long updatedAt) {
        if (current == null || current.status() != CoreTriggerOrderStatus.TRIGGERING)
            throw new IllegalStateException("invalid rejected trigger completion");
        return transition(current, CoreTriggerOrderStatus.TRIGGER_FAILED, 0,
                current.triggerSequence(), current.triggeredPriceTicks(), reason, updatedAt);
    }

    private static void validatePlacement(TradingRuntimeState runtime, long userId, int symbolId,
                                          long positionKey, CoreTriggerOrderStateView view) {
        UserRuntime user = runtime.user(userId);
        if (user == null) throw new CoreStateRejectedException("USER_NOT_FOUND", "user does not exist");
        if (!runtime.productLine().isDerivative()) return;
        if (user.positionMode() == CorePositionMode.ONE_WAY && view.positionSide().hedgeSide()
                || user.positionMode() == CorePositionMode.HEDGE && !view.positionSide().hedgeSide()) {
            throw new CoreStateRejectedException("POSITION_MODE_MISMATCH",
                    "trigger position side does not match user position mode");
        }
        PositionRuntime position = runtime.position(positionKey);
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
        long openReduceOnly = runtime.openReduceOnlyQuantity(
                userId, symbolId, view.positionSide(), closeSide, view.marginMode());
        long projectedTriggerCapacity = runtime.projectedTriggerCapacity(userId, view);
        if (Math.addExact(openReduceOnly, projectedTriggerCapacity)
                > Math.absExact(position.signedQuantitySteps())) {
            throw new CoreStateRejectedException("TRIGGER_CLOSE_CAPACITY_EXCEEDED",
                    "trigger order quantity exceeds available position");
        }
    }

    private static void update(TradingRuntimeState runtime, CoreTriggerOrderState current,
                               CoreTriggerOrderStatus status, long placedOrderId, long triggerSequence,
                               long triggeredPriceTicks, String rejectReason, long updatedAt) {
        runtime.putTriggerOrder(transition(current, status, placedOrderId, triggerSequence,
                triggeredPriceTicks, rejectReason, updatedAt));
        runtime.incrementCommandRevision();
    }

    private static CoreTriggerOrderState transition(CoreTriggerOrderState current,
                                                    CoreTriggerOrderStatus status, long placedOrderId,
                                                    long triggerSequence, long triggeredPriceTicks,
                                                    String rejectReason, long updatedAt) {
        return new CoreTriggerOrderState(current.triggerOrderId(), current.productLine(), current.userId(),
                current.clientTriggerOrderId(), current.ocoGroupId(), current.symbol(), current.side(),
                current.triggerType(), current.triggerCondition(), current.triggerPriceTicks(),
                current.activationPriceTicks(), current.callbackRatePpm(), current.highestPriceTicks(),
                current.lowestPriceTicks(), current.activatedAtEpochMillis(), current.orderType(),
                current.timeInForce(), current.priceTicks(), current.quantitySteps(), current.marginMode(),
                current.positionSide(), status, placedOrderId, triggerSequence, triggeredPriceTicks, rejectReason,
                current.traceId(), current.expiresAtEpochMillis(),
                status == CoreTriggerOrderStatus.TRIGGERED || status == CoreTriggerOrderStatus.TRIGGER_FAILED
                        ? updatedAt : current.triggeredAtEpochMillis(),
                current.createdAtEpochMillis(), updatedAt, Math.incrementExact(current.revision()),
                current.instrumentChangeId(), current.makerFeeRatePpm(), current.takerFeeRatePpm());
    }

    private static CoreTriggerOrderState require(TradingRuntimeState runtime, long triggerOrderId) {
        if (runtime == null || triggerOrderId <= 0) throw new IllegalArgumentException("invalid runtime trigger order");
        runtime.assertOwner();
        CoreTriggerOrderState current = runtime.triggerOrder(triggerOrderId);
        if (current == null) {
            throw new CoreStateRejectedException("TRIGGER_ORDER_NOT_FOUND", "trigger order not found");
        }
        return current;
    }

    private static void requireInput(TradingRuntimeState runtime, long userId, long triggerOrderId) {
        if (runtime == null || userId <= 0 || triggerOrderId <= 0) {
            throw new IllegalArgumentException("invalid runtime trigger order");
        }
    }
}
