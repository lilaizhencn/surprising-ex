package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CorePositionMode;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.ReservationKind;

public final class RuntimeOrderAdmission {

    private static final long PPM = 1_000_000L;

    private RuntimeOrderAdmission() {
    }

    public static long requiredReservation(
            TradingRuntimeState runtime, RuntimeIdentityRegistry identities, long userId,
            ResolvedPlaceOrder order, long openInterestSteps, AdmissionOrderIndex activeOrders) {
        return requiredReservation(runtime, identities, userId, order, openInterestSteps, activeOrders, 0);
    }

    public static long requiredReservation(
            TradingRuntimeState runtime, RuntimeIdentityRegistry identities, long userId,
            ResolvedPlaceOrder order, long openInterestSteps, AdmissionOrderIndex activeOrders,
            long excludedOrderId) {
        if (runtime == null || identities == null || order == null || activeOrders == null || userId <= 0) {
            throw new IllegalArgumentException("invalid runtime order admission input");
        }
        AdmissionIdentity identity = admissionIdentity(runtime, identities, userId, order);
        String excludedSymbol = null;
        if (excludedOrderId != 0) {
            OrderRuntime excluded = runtime.order(excludedOrderId);
            if (excluded != null) excludedSymbol = identities.symbol(excluded.symbolId());
        }
        return requiredReservation(runtime, userId, order, openInterestSteps, activeOrders,
                excludedOrderId, identity, excludedSymbol);
    }

    public static AdmissionIdentity admissionIdentity(
            TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
            long userId, ResolvedPlaceOrder order) {
        if (runtime == null || identities == null || order == null || userId <= 0) {
            throw new IllegalArgumentException("invalid runtime order admission identity");
        }
        String positionIdentity = order.positionSide() == CorePositionSide.NET
                ? OrderReservation.normalizeSymbol(order.symbol())
                : OrderReservation.normalizeSymbol(order.symbol()) + ':' + order.positionSide().name();
        int symbolId = order.symbolId();
        boolean lifecycleSettled = symbolId >= 0
                && runtime.treasury().lifecycleSettlement(symbolId) != 0;
        return new AdmissionIdentity(identities.findClientKey(userId, order.clientOrderId()),
                symbolId < 0 ? null : symbolId,
                identities.findPositionKey(userId, positionIdentity), lifecycleSettled);
    }

    public static long requiredReservationPrepared(
            TradingRuntimeState runtime, long userId, ResolvedPlaceOrder order,
            long openInterestSteps, AdmissionOrderIndex activeOrders, AdmissionIdentity identity) {
        if (runtime == null || order == null || activeOrders == null || identity == null || userId <= 0) {
            throw new IllegalArgumentException("invalid prepared runtime order admission input");
        }
        return requiredReservation(runtime, userId, order, openInterestSteps, activeOrders,
                0, identity, null);
    }

    private static long requiredReservation(
            TradingRuntimeState runtime, long userId, ResolvedPlaceOrder order,
            long openInterestSteps, AdmissionOrderIndex activeOrders, long excludedOrderId,
            AdmissionIdentity identity, String excludedSymbol) {
        if (runtime.order(order.orderId()) != null && order.orderId() != excludedOrderId) {
            throw rejected("DUPLICATE_ORDER_ID", "orderId already exists");
        }
        Long clientOrderId = identity.clientKey() == null
                ? null : runtime.orderIdByClient(userId, identity.clientKey());
        if (clientOrderId != null && clientOrderId != excludedOrderId) {
            throw rejected("DUPLICATE_CLIENT_ORDER_ID", "clientOrderId already exists");
        }
        CoreInstrumentState instrument = runtime.instrument(order.symbol());
        if (instrument == null || instrument.version() != order.instrumentVersion()
                || !instrument.equals(order.instrument())) {
            throw rejected("INSTRUMENT_ORDER_MISMATCH", "order instrument differs from Runtime");
        }
        if (identity.lifecycleSettled()) {
            throw rejected("INSTRUMENT_SETTLED", "instrument is already settled");
        }
        validateReservation(runtime.productLine().isDerivative(), instrument, order);
        UserRuntime user = runtime.user(userId);
        CorePositionMode positionMode = user == null ? CorePositionMode.ONE_WAY : user.positionMode();
        PositionRuntime position = identity.positionKey() == null ? null : runtime.position(identity.positionKey());
        OrderRuntime excluded = excludedOrderId == 0 ? null : runtime.order(excludedOrderId);
        if (excludedOrderId != 0 && (excluded == null || excluded.userId() != userId
                || !order.symbol().equals(excludedSymbol))) {
            throw rejected("ORDER_NOT_FOUND", "excluded replacement order is invalid");
        }
        CoreMarginMode conflictingMode = order.marginMode() == CoreMarginMode.CROSS
                ? CoreMarginMode.ISOLATED : CoreMarginMode.CROSS;
        AdmissionSummary admissionSummary = activeOrders.inspect(
                userId, order.symbol(), order.positionSide(), order.side(), conflictingMode);
        validatePositionIdentity(
                positionMode, position, order, admissionSummary, conflictingMode, excluded);
        validateReduceOnly(runtime.productLine().isDerivative(), position, order,
                admissionSummary, userId, excluded);
        long leverage = effectiveLeverage(runtime, instrument, order, userId);
        validateRiskLimits(runtime, instrument, position, order, admissionSummary, userId,
                openInterestSteps, excluded, leverage);
        return reservationUnits(instrument, position, order, leverage);
    }

    private static void validateReservation(
            boolean derivative, CoreInstrumentState instrument, ResolvedPlaceOrder order) {
        String asset = AssetBalance.normalizeAsset(order.reservationAsset());
        if (derivative) {
            if (order.reservationKind() != ReservationKind.DERIVATIVE_MARGIN) {
                throw rejected("INVALID_RESERVATION_KIND", "derivative order must reserve margin");
            }
            if (!asset.equals(instrument.settleAsset())) {
                throw rejected("INVALID_DERIVATIVE_RESERVATION_ASSET", "invalid derivative reservation asset");
            }
        } else {
            if (order.reservationKind() != ReservationKind.SPOT_ASSET) {
                throw rejected("INVALID_RESERVATION_KIND", "spot order must reserve an asset");
            }
            String expected = order.side() == CoreOrderSide.BUY
                    ? instrument.quoteAsset() : instrument.baseAsset();
            if (!asset.equals(expected)) {
                throw rejected("INVALID_SPOT_RESERVATION_ASSET", "invalid spot reservation asset");
            }
        }
        if (order.matchingPriceTicks() <= 0) {
            throw rejected("INVALID_ORDER_PRICE", "matching price must be positive");
        }
    }

    private static void validatePositionIdentity(
            CorePositionMode mode, PositionRuntime position, ResolvedPlaceOrder order,
            AdmissionSummary admissionSummary, CoreMarginMode conflictingMode, OrderRuntime excluded) {
        if ((mode == CorePositionMode.ONE_WAY && order.positionSide().hedgeSide())
                || (mode == CorePositionMode.HEDGE && !order.positionSide().hedgeSide())) {
            throw rejected("POSITION_MODE_MISMATCH", "position side differs from user position mode");
        }
        if (order.marginMode() == CoreMarginMode.ISOLATED
                && order.reservationKind() == ReservationKind.SPOT_ASSET) {
            throw rejected("POSITION_MARGIN_ADJUSTMENT_INVALID", "spot order cannot use isolated margin");
        }
        boolean positionConflict = position != null && position.signedQuantitySteps() != 0
                && position.marginMode() != order.marginMode();
        int conflictingOrders = admissionSummary.marginModeCount();
        if (excluded != null && excluded.status() == CoreOrderStatus.OPEN
                && excluded.positionSide() == order.positionSide()
                && excluded.marginMode() == conflictingMode) {
            conflictingOrders--;
        }
        if (positionConflict || conflictingOrders > 0) {
            throw rejected("POSITION_MARGIN_ADJUSTMENT_INVALID", "margin mode switch requires an empty position");
        }
        if ((order.positionSide() == CorePositionSide.LONG
                && order.reduceOnly() == (order.side() == CoreOrderSide.BUY))
                || (order.positionSide() == CorePositionSide.SHORT
                && order.reduceOnly() == (order.side() == CoreOrderSide.SELL))) {
            throw rejected("POSITION_MODE_MISMATCH", "hedge side and direction are inconsistent");
        }
    }

    private static void validateReduceOnly(
            boolean derivative, PositionRuntime position, ResolvedPlaceOrder order,
            AdmissionSummary admissionSummary, long userId, OrderRuntime excluded) {
        if (!order.reduceOnly()) return;
        if (!derivative) throw rejected("REDUCE_ONLY_UNSUPPORTED", "spot order cannot be reduce-only");
        if (position == null || position.signedQuantitySteps() == 0
                || (position.signedQuantitySteps() > 0) == (order.side() == CoreOrderSide.BUY)) {
            throw rejected("REDUCE_ONLY_REQUIRES_POSITION_STATE", "reduce-only order must close a position");
        }
        long positionSteps = Math.absExact(position.signedQuantitySteps());
        long committedOrders = admissionSummary.reduceOnlyQuantity();
        if (excluded != null && excluded.status() == CoreOrderStatus.OPEN && excluded.reduceOnly()
                && excluded.userId() == userId && excluded.side() == order.side()) {
            committedOrders = Math.subtractExact(committedOrders, excluded.remainingQuantitySteps());
        }
        long committed = Math.min(positionSteps, committedOrders);
        if (order.quantitySteps() > Math.subtractExact(positionSteps, committed)) {
            throw rejected("REDUCE_ONLY_CAPACITY_EXCEEDED", "reduce-only quantity exceeds position capacity");
        }
    }

    private static void validateRiskLimits(
            TradingRuntimeState runtime, CoreInstrumentState instrument, PositionRuntime position,
            ResolvedPlaceOrder order, AdmissionSummary admissionSummary, long userId, long openInterestSteps,
            OrderRuntime excluded, long leverage) {
        if (!runtime.productLine().isDerivative() || order.reduceOnly()) return;
        long current = position == null ? 0 : position.signedQuantitySteps();
        long pending = admissionSummary.pendingQuantity();
        if (excluded != null && excluded.status() == CoreOrderStatus.OPEN && !excluded.reduceOnly()
                && excluded.userId() == userId && excluded.positionSide() == order.positionSide()
                && excluded.side() == order.side()) {
            pending = Math.subtractExact(pending, excluded.remainingQuantitySteps());
        }
        long totalOrders = Math.addExact(pending, order.quantitySteps());
        long signedOrders = order.side() == CoreOrderSide.BUY ? totalOrders : Math.negateExact(totalOrders);
        long projectedSteps = Math.absExact(Math.addExact(current, signedOrders));
        long projectedNotional = CoreContractMath.notionalUnits(
                instrument, projectedSteps, order.markPriceTicks());
        if (projectedNotional > instrument.maxPositionNotionalUnits()) {
            throw rejected("POSITION_NOTIONAL_LIMIT_EXCEEDED", "projected position exceeds instrument limit");
        }
        long openInterestNotional = openInterestSteps == 0 ? 0
                : CoreContractMath.notionalUnits(instrument, openInterestSteps, order.markPriceTicks());
        long scaledLimit = CoreContractMath.scaledFloorCapped(
                openInterestNotional, instrument.userOpenInterestLimitRatePpm(), PPM,
                instrument.userOpenInterestLimitFloorUnits(), instrument.maxPositionNotionalUnits());
        if (projectedNotional > scaledLimit) {
            throw rejected("OPEN_INTEREST_LIMIT_EXCEEDED", "projected position exceeds open-interest limit");
        }
        var bracket = CoreContractMath.riskBracket(instrument, projectedNotional);
        if (projectedNotional > bracket.notionalCapUnits()) {
            throw rejected("RISK_BRACKET_EXCEEDED", "projected position exceeds risk bracket");
        }
        if (leverage > bracket.maxLeveragePpm()
                || CoreContractMath.initialMarginRateFromLeverage(leverage) < bracket.initialMarginRatePpm()) {
            throw rejected("LEVERAGE_EXCEEDS_RISK_BRACKET", "configured leverage exceeds risk bracket");
        }
    }

    private static long reservationUnits(
            CoreInstrumentState instrument, PositionRuntime position,
            ResolvedPlaceOrder order, long leverage) {
        if (instrument.contractType() == com.surprising.instrument.api.model.ContractType.SPOT) {
            if (order.side() == CoreOrderSide.SELL) return order.quantitySteps();
            long notional = Math.multiplyExact(order.reservationPriceTicks(), order.quantitySteps());
            long feeDebit = fragmentationSafeFeeDebit(instrument, order);
            return Math.addExact(notional, feeDebit);
        }
        long current = position == null ? 0 : position.signedQuantitySteps();
        long signedOrder = order.side() == CoreOrderSide.BUY
                ? order.quantitySteps() : Math.negateExact(order.quantitySteps());
        long openSteps = order.reduceOnly() ? 0 : order.quantitySteps();
        long projectedRisk = Math.addExact(Math.absExact(current), order.quantitySteps());
        long projectedSigned = signedOrder > 0 ? projectedRisk : Math.negateExact(projectedRisk);
        long margin = openingMargin(instrument, projectedSigned, signedOrder, openSteps,
                order.reservationPriceTicks(), leverage);
        long premium = instrument.contractType().isOption() && order.side() == CoreOrderSide.BUY
                ? CoreContractMath.optionPremiumUnits(instrument, order.reservationPriceTicks(), order.quantitySteps())
                : 0;
        long feeDebit = fragmentationSafeFeeDebit(instrument, order);
        return Math.max(1, Math.addExact(Math.addExact(margin, premium), feeDebit));
    }

    private static long effectiveLeverage(
            TradingRuntimeState runtime, CoreInstrumentState instrument,
            ResolvedPlaceOrder order, long userId) {
        if (!runtime.productLine().isDerivative()) return instrument.maxLeveragePpm();
        Long configured = runtime.leverage(new CoreLeverageKey(userId, instrument.symbol(), order.marginMode()));
        return configured == null ? instrument.maxLeveragePpm() : configured;
    }

    private static long fragmentationSafeFeeDebit(CoreInstrumentState instrument, ResolvedPlaceOrder order) {
        long feePerStep = CoreContractMath.feeDeltaUnits(
                instrument, order.reservationPriceTicks(), 1, order.takerFeeRatePpm());
        long debitPerStep = Math.max(0, Math.negateExact(feePerStep));
        return Math.multiplyExact(debitPerStep, order.quantitySteps());
    }

    private static long openingMargin(
            CoreInstrumentState instrument, long projectedQuantity, long signedFill, long openSteps,
            long priceTicks, long leveragePpm) {
        if (openSteps == 0 || instrument.contractType().isOption() && signedFill > 0) return 0;
        long projectedNotional = CoreContractMath.notionalUnits(
                instrument, Math.absExact(projectedQuantity), priceTicks);
        var bracket = CoreContractMath.maintenanceRiskBracket(instrument, projectedNotional);
        long rate = Math.max(Math.max(instrument.initialMarginRatePpm(), bracket.initialMarginRatePpm()),
                CoreContractMath.initialMarginRateFromLeverage(leveragePpm));
        return CoreContractMath.openingMarginUnits(instrument,
                signedFill > 0 ? CoreOrderSide.BUY : CoreOrderSide.SELL, priceTicks, openSteps, rate);
    }

    private static CoreStateRejectedException rejected(String code, String message) {
        return new CoreStateRejectedException(code, message);
    }

    public interface AdmissionOrderIndex {
        AdmissionSummary inspect(long userId, String symbol, CorePositionSide positionSide,
                                 CoreOrderSide side, CoreMarginMode conflictingMarginMode);

        default void admitted(long userId, ResolvedPlaceOrder order) {
        }
    }

    public static final class AdmissionSummary {
        private long pendingQuantity;
        private long reduceOnlyQuantity;
        private int marginModeCount;

        public long pendingQuantity() {
            return pendingQuantity;
        }

        public long reduceOnlyQuantity() {
            return reduceOnlyQuantity;
        }

        public int marginModeCount() {
            return marginModeCount;
        }

        public AdmissionSummary set(long pendingQuantity, long reduceOnlyQuantity, int marginModeCount) {
            this.pendingQuantity = pendingQuantity;
            this.reduceOnlyQuantity = reduceOnlyQuantity;
            this.marginModeCount = marginModeCount;
            return this;
        }
    }

    public record AdmissionIdentity(
            Long clientKey, Integer symbolId, Long positionKey, boolean lifecycleSettled) {
    }
}
