package com.surprising.trading.order.model;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.api.model.TimeInForce;
import java.time.Instant;

public record OrderRecord(
        long orderId,
        ProductLine productLine,
        long userId,
        String clientOrderId,
        String symbol,
        long instrumentVersion,
        OrderSide side,
        OrderType orderType,
        TimeInForce timeInForce,
        long priceTicks,
        long quantitySteps,
        long executedQuantitySteps,
        long remainingQuantitySteps,
        MarginMode marginMode,
        PositionSide positionSide,
        long makerFeeRatePpm,
        long takerFeeRatePpm,
        boolean reduceOnly,
        boolean postOnly,
        String reservationAccountType,
        String reservationAsset,
        long reservedUnits,
        OrderStatus status,
        String rejectReason,
        Instant createdAt,
        Instant updatedAt,
        long revision) {

    public OrderRecord {
        productLine = productLine == null ? ProductLine.LINEAR_PERPETUAL : productLine;
        marginMode = MarginMode.defaultIfNull(marginMode);
        positionSide = PositionSide.defaultIfNull(positionSide);
        revision = Math.max(1L, revision);
        if (reservedUnits < 0L) {
            throw new IllegalArgumentException("reservedUnits must be non-negative");
        }
        if (reservedUnits == 0L) {
            reservationAccountType = null;
            reservationAsset = null;
        } else if (reservationAccountType == null || reservationAccountType.isBlank()
                || reservationAsset == null || reservationAsset.isBlank()) {
            throw new IllegalArgumentException("positive reservation requires account type and asset");
        }
    }

    public OrderRecord(long orderId,
                       ProductLine productLine,
                       long userId,
                       String clientOrderId,
                       String symbol,
                       long instrumentVersion,
                       OrderSide side,
                       OrderType orderType,
                       TimeInForce timeInForce,
                       long priceTicks,
                       long quantitySteps,
                       long executedQuantitySteps,
                       long remainingQuantitySteps,
                       MarginMode marginMode,
                       PositionSide positionSide,
                       long makerFeeRatePpm,
                       long takerFeeRatePpm,
                       boolean reduceOnly,
                       boolean postOnly,
                       OrderStatus status,
                       String rejectReason,
                       Instant createdAt,
                       Instant updatedAt,
                       long revision) {
        this(orderId, productLine, userId, clientOrderId, symbol, instrumentVersion, side, orderType, timeInForce,
                priceTicks, quantitySteps, executedQuantitySteps, remainingQuantitySteps, marginMode, positionSide,
                makerFeeRatePpm, takerFeeRatePpm, reduceOnly, postOnly, null, null, 0L,
                status, rejectReason, createdAt, updatedAt, revision);
    }

    public OrderRecord(long orderId,
                       long userId,
                       String clientOrderId,
                       String symbol,
                       long instrumentVersion,
                       OrderSide side,
                       OrderType orderType,
                       TimeInForce timeInForce,
                       long priceTicks,
                       long quantitySteps,
                       long executedQuantitySteps,
                       long remainingQuantitySteps,
                       MarginMode marginMode,
                       PositionSide positionSide,
                       long makerFeeRatePpm,
                       long takerFeeRatePpm,
                       boolean reduceOnly,
                       boolean postOnly,
                       OrderStatus status,
                       String rejectReason,
                       Instant createdAt,
                       Instant updatedAt) {
        this(orderId, ProductLine.LINEAR_PERPETUAL, userId, clientOrderId, symbol, instrumentVersion, side,
                orderType, timeInForce, priceTicks, quantitySteps, executedQuantitySteps, remainingQuantitySteps,
                marginMode, positionSide, makerFeeRatePpm, takerFeeRatePpm, reduceOnly, postOnly,
                null, null, 0L, status,
                rejectReason, createdAt, updatedAt, 1L);
    }

    public OrderRecord(long orderId,
                       long userId,
                       String clientOrderId,
                       String symbol,
                       long instrumentVersion,
                       OrderSide side,
                       OrderType orderType,
                       TimeInForce timeInForce,
                       long priceTicks,
                       long quantitySteps,
                       long executedQuantitySteps,
                       long remainingQuantitySteps,
                       MarginMode marginMode,
                       long makerFeeRatePpm,
                       long takerFeeRatePpm,
                       boolean reduceOnly,
                       boolean postOnly,
                       OrderStatus status,
                       String rejectReason,
                       Instant createdAt,
                       Instant updatedAt) {
        this(orderId, ProductLine.LINEAR_PERPETUAL, userId, clientOrderId, symbol, instrumentVersion, side, orderType, timeInForce,
                priceTicks, quantitySteps, executedQuantitySteps, remainingQuantitySteps, marginMode,
                PositionSide.NET,
                makerFeeRatePpm, takerFeeRatePpm, reduceOnly, postOnly, null, null, 0L,
                status, rejectReason, createdAt, updatedAt, 1L);
    }

    public OrderRecord(long orderId,
                       long userId,
                       String clientOrderId,
                       String symbol,
                       long instrumentVersion,
                       OrderSide side,
                       OrderType orderType,
                       TimeInForce timeInForce,
                       long priceTicks,
                       long quantitySteps,
                       long executedQuantitySteps,
                       long remainingQuantitySteps,
                       long makerFeeRatePpm,
                       long takerFeeRatePpm,
                       boolean reduceOnly,
                       boolean postOnly,
                       OrderStatus status,
                       String rejectReason,
                       Instant createdAt,
                       Instant updatedAt) {
        this(orderId, ProductLine.LINEAR_PERPETUAL, userId, clientOrderId, symbol, instrumentVersion, side, orderType, timeInForce,
                priceTicks, quantitySteps, executedQuantitySteps, remainingQuantitySteps, MarginMode.CROSS,
                PositionSide.NET,
                makerFeeRatePpm, takerFeeRatePpm, reduceOnly, postOnly, null, null, 0L,
                status, rejectReason, createdAt, updatedAt, 1L);
    }
}
