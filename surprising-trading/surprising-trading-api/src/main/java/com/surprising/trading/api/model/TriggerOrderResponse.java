package com.surprising.trading.api.model;

import java.time.Instant;

public record TriggerOrderResponse(
        long triggerOrderId,
        long userId,
        String clientTriggerOrderId,
        String ocoGroupId,
        String symbol,
        OrderSide side,
        TriggerOrderType triggerType,
        TriggerPriceType triggerPriceType,
        TriggerCondition triggerCondition,
        long triggerPriceTicks,
        OrderType orderType,
        TimeInForce timeInForce,
        long priceTicks,
        long quantitySteps,
        MarginMode marginMode,
        TriggerOrderStatus status,
        Long placedOrderId,
        Long triggerSequence,
        Long triggeredPriceTicks,
        String rejectReason,
        String traceId,
        Instant expiresAt,
        Instant triggeredAt,
        Instant createdAt,
        Instant updatedAt) {

    public TriggerOrderResponse {
        marginMode = MarginMode.defaultIfNull(marginMode);
    }
}
