package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CorePositionMode;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.ReservationKind;
import java.math.BigInteger;

public final class RuntimeOrderAdmission {

    private static final long PPM = 1_000_000L;

    private RuntimeOrderAdmission() {
    }

    public static long requiredReservation(
            TradingRuntimeState runtime, RuntimeIdentityRegistry identities, long userId,
            ResolvedPlaceOrder order, long openInterestSteps, ActiveOrderIndex activeOrders) {
        return requiredReservation(runtime, identities, userId, order, openInterestSteps, activeOrders, 0);
    }

    public static long requiredReservation(
            TradingRuntimeState runtime, RuntimeIdentityRegistry identities, long userId,
            ResolvedPlaceOrder order, long openInterestSteps, ActiveOrderIndex activeOrders,
            long excludedOrderId) {
        if (runtime == null || identities == null || order == null || activeOrders == null || userId <= 0) {
            throw new IllegalArgumentException("invalid runtime order admission input");
        }
        if (runtime.order(order.orderId()) != null && order.orderId() != excludedOrderId) {
            throw rejected("DUPLICATE_ORDER_ID", "orderId already exists");
        }
        Long clientKey = identities.findClientKey(userId, order.clientOrderId());
        Long clientOrderId = clientKey == null ? null : runtime.orderIdByClient(userId, clientKey);
        if (clientOrderId != null && clientOrderId != excludedOrderId) {
            throw rejected("DUPLICATE_CLIENT_ORDER_ID", "clientOrderId already exists");
        }
        CoreInstrumentState instrument = runtime.instrument(order.symbol());
        if (instrument == null || instrument.version() != order.instrumentVersion()
                || !instrument.equals(order.instrument())) {
            throw rejected("INSTRUMENT_ORDER_MISMATCH", "order instrument differs from Runtime");
        }
        Integer symbolId = identities.findSymbolId(order.symbol());
        if (symbolId != null && runtime.treasury().lifecycleSettlement(symbolId) != 0) {
            throw rejected("INSTRUMENT_SETTLED", "instrument is already settled");
        }
        validateReservation(runtime.productLine().isDerivative(), instrument, order);
        UserRuntime user = runtime.user(userId);
        CorePositionMode positionMode = user == null ? CorePositionMode.ONE_WAY : user.positionMode();
        PositionRuntime position = position(runtime, identities, userId, order.symbol(), order.positionSide());
        OrderRuntime excluded = excludedOrderId == 0 ? null : runtime.order(excludedOrderId);
        if (excludedOrderId != 0 && (excluded == null || excluded.userId() != userId
                || !identities.symbol(excluded.symbolId()).equals(order.symbol()))) {
            throw rejected("ORDER_NOT_FOUND", "excluded replacement order is invalid");
        }
        validatePositionIdentity(positionMode, position, order, activeOrders, userId, excluded);
        validateReduceOnly(runtime.productLine().isDerivative(), position, order, activeOrders, userId, excluded);
        validateRiskLimits(runtime, instrument, position, order, activeOrders, userId, openInterestSteps, excluded);
        return reservationUnits(runtime, instrument, position, order, userId);
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
            ActiveOrderIndex activeOrders, long userId, OrderRuntime excluded) {
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
        boolean orderConflict = false;
        for (CoreMarginMode candidate : CoreMarginMode.values()) {
            if (candidate == order.marginMode()) continue;
            int count = activeOrders.marginModeCount(userId, order.symbol(), order.positionSide(), candidate);
            if (excluded != null && excluded.status() == CoreOrderStatus.OPEN
                    && excluded.positionSide() == order.positionSide() && excluded.marginMode() == candidate) {
                count--;
            }
            if (count > 0) orderConflict = true;
        }
        if (positionConflict || orderConflict) {
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
            ActiveOrderIndex activeOrders, long userId, OrderRuntime excluded) {
        if (!order.reduceOnly()) return;
        if (!derivative) throw rejected("REDUCE_ONLY_UNSUPPORTED", "spot order cannot be reduce-only");
        if (position == null || position.signedQuantitySteps() == 0
                || (position.signedQuantitySteps() > 0) == (order.side() == CoreOrderSide.BUY)) {
            throw rejected("REDUCE_ONLY_REQUIRES_POSITION_STATE", "reduce-only order must close a position");
        }
        long positionSteps = Math.absExact(position.signedQuantitySteps());
        long committedOrders = activeOrders.reduceOnlyQuantity(userId, order.symbol(), order.side());
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
            ResolvedPlaceOrder order, ActiveOrderIndex activeOrders, long userId, long openInterestSteps,
            OrderRuntime excluded) {
        if (!runtime.productLine().isDerivative() || order.reduceOnly()) return;
        long current = position == null ? 0 : position.signedQuantitySteps();
        long pending = activeOrders.pendingQuantity(userId, instrument.symbol(),
                order.positionSide(), order.side());
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
        long scaledLimit = BigInteger.valueOf(openInterestNotional)
                .multiply(BigInteger.valueOf(instrument.userOpenInterestLimitRatePpm()))
                .divide(BigInteger.valueOf(PPM))
                .max(BigInteger.valueOf(instrument.userOpenInterestLimitFloorUnits()))
                .min(BigInteger.valueOf(instrument.maxPositionNotionalUnits())).longValueExact();
        if (projectedNotional > scaledLimit) {
            throw rejected("OPEN_INTEREST_LIMIT_EXCEEDED", "projected position exceeds open-interest limit");
        }
        var bracket = CoreContractMath.riskBracket(instrument, projectedNotional);
        if (projectedNotional > bracket.notionalCapUnits()) {
            throw rejected("RISK_BRACKET_EXCEEDED", "projected position exceeds risk bracket");
        }
        Long configured = runtime.leverage(new CoreLeverageKey(userId, instrument.symbol(), order.marginMode()));
        long leverage = configured == null ? instrument.maxLeveragePpm() : configured;
        if (leverage > bracket.maxLeveragePpm()
                || initialMarginRateFromLeverage(leverage) < bracket.initialMarginRatePpm()) {
            throw rejected("LEVERAGE_EXCEEDS_RISK_BRACKET", "configured leverage exceeds risk bracket");
        }
    }

    private static long reservationUnits(
            TradingRuntimeState runtime, CoreInstrumentState instrument, PositionRuntime position,
            ResolvedPlaceOrder order, long userId) {
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
        Long configured = runtime.leverage(new CoreLeverageKey(userId, instrument.symbol(), order.marginMode()));
        long leverage = configured == null ? instrument.maxLeveragePpm() : configured;
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

    private static long fragmentationSafeFeeDebit(CoreInstrumentState instrument, ResolvedPlaceOrder order) {
        long feePerStep = CoreContractMath.feeDeltaUnits(
                instrument, order.reservationPriceTicks(), 1, order.takerFeeRatePpm());
        long debitPerStep = Math.max(0, Math.negateExact(feePerStep));
        return Math.multiplyExact(debitPerStep, order.quantitySteps());
    }

    private static long openingMargin(
            CoreInstrumentState instrument, long projectedQuantity, long signedFill, long openSteps,
            long priceTicks, long leveragePpm) {
        if (openSteps == 0) return 0;
        long projectedNotional = CoreContractMath.notionalUnits(
                instrument, Math.absExact(projectedQuantity), priceTicks);
        var bracket = CoreContractMath.maintenanceRiskBracket(instrument, projectedNotional);
        long rate = Math.max(Math.max(instrument.initialMarginRatePpm(), bracket.initialMarginRatePpm()),
                initialMarginRateFromLeverage(leveragePpm));
        return CoreContractMath.openingMarginUnits(instrument,
                signedFill > 0 ? CoreOrderSide.BUY : CoreOrderSide.SELL, priceTicks, openSteps, rate);
    }

    private static PositionRuntime position(
            TradingRuntimeState runtime, RuntimeIdentityRegistry identities, long userId,
            String symbol, CorePositionSide side) {
        String key = side == CorePositionSide.NET ? OrderReservation.normalizeSymbol(symbol)
                : OrderReservation.normalizeSymbol(symbol) + ':' + side.name();
        Long positionKey = identities.findPositionKey(userId, key);
        return positionKey == null ? null : runtime.position(positionKey);
    }

    private static long initialMarginRateFromLeverage(long leveragePpm) {
        BigInteger numerator = BigInteger.valueOf(PPM).multiply(BigInteger.valueOf(PPM));
        BigInteger[] quotient = numerator.divideAndRemainder(BigInteger.valueOf(leveragePpm));
        return (quotient[1].signum() == 0 ? quotient[0] : quotient[0].add(BigInteger.ONE)).longValueExact();
    }

    private static CoreStateRejectedException rejected(String code, String message) {
        return new CoreStateRejectedException(code, message);
    }
}
