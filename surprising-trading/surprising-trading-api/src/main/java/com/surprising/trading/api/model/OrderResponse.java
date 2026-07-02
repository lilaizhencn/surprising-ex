package com.surprising.trading.api.model;

import java.time.Instant;

public record OrderResponse(
        long orderId,
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

    public OrderResponse {
        marginMode = MarginMode.defaultIfNull(marginMode);
        positionSide = PositionSide.defaultIfNull(positionSide);
    }

    public OrderResponse(long orderId,
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
        this(orderId, userId, clientOrderId, symbol, instrumentVersion, side, orderType, timeInForce,
                priceTicks, quantitySteps, executedQuantitySteps, remainingQuantitySteps, marginMode, PositionSide.NET,
                makerFeeRatePpm, takerFeeRatePpm, reduceOnly, postOnly, status, rejectReason, createdAt, updatedAt);
    }

    public OrderResponse(long orderId,
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
                         boolean reduceOnly,
                         boolean postOnly,
                         OrderStatus status,
                         String rejectReason,
                         Instant createdAt,
                         Instant updatedAt) {
        this(orderId, userId, clientOrderId, symbol, instrumentVersion, side, orderType, timeInForce,
                priceTicks, quantitySteps, executedQuantitySteps, remainingQuantitySteps, MarginMode.CROSS,
                PositionSide.NET,
                0L, 0L, reduceOnly, postOnly, status, rejectReason, createdAt, updatedAt);
    }
}
