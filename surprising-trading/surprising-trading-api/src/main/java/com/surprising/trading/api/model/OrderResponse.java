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
        boolean reduceOnly,
        boolean postOnly,
        OrderStatus status,
        String rejectReason,
        Instant createdAt,
        Instant updatedAt) {
}
