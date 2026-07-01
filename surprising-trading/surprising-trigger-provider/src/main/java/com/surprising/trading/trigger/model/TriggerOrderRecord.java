package com.surprising.trading.trigger.model;

import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.api.model.TriggerCondition;
import com.surprising.trading.api.model.TriggerOrderStatus;
import com.surprising.trading.api.model.TriggerOrderType;
import com.surprising.trading.api.model.TriggerPriceType;
import java.time.Instant;

public record TriggerOrderRecord(
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
}
