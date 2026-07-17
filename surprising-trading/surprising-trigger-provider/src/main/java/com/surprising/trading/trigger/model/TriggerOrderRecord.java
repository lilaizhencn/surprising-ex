package com.surprising.trading.trigger.model;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.api.model.TriggerCondition;
import com.surprising.trading.api.model.TriggerOrderStatus;
import com.surprising.trading.api.model.TriggerOrderType;
import java.time.Instant;

public record TriggerOrderRecord(
        long triggerOrderId,
        ProductLine productLine,
        long userId,
        String clientTriggerOrderId,
        String ocoGroupId,
        String symbol,
        OrderSide side,
        TriggerOrderType triggerType,
        TriggerCondition triggerCondition,
        long triggerPriceTicks,
        Long activationPriceTicks,
        Long callbackRatePpm,
        Long highestPriceTicks,
        Long lowestPriceTicks,
        Instant activatedAt,
        OrderType orderType,
        TimeInForce timeInForce,
        long priceTicks,
        long quantitySteps,
        MarginMode marginMode,
        PositionSide positionSide,
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

    public TriggerOrderRecord {
        productLine = productLine == null ? ProductLine.LINEAR_PERPETUAL : productLine;
        marginMode = MarginMode.defaultIfNull(marginMode);
        positionSide = PositionSide.defaultIfNull(positionSide);
    }

    public TriggerOrderRecord(long triggerOrderId,
                              long userId,
                              String clientTriggerOrderId,
                              String ocoGroupId,
                              String symbol,
                              OrderSide side,
                              TriggerOrderType triggerType,
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
        this(triggerOrderId, ProductLine.LINEAR_PERPETUAL, userId, clientTriggerOrderId, ocoGroupId, symbol, side,
                triggerType, triggerCondition, triggerPriceTicks, null, null, null, null, null, orderType, timeInForce,
                priceTicks, quantitySteps, marginMode, PositionSide.NET, status, placedOrderId, triggerSequence,
                triggeredPriceTicks, rejectReason, traceId, expiresAt, triggeredAt, createdAt, updatedAt);
    }

    public TriggerOrderRecord(long triggerOrderId,
                              long userId,
                              String clientTriggerOrderId,
                              String ocoGroupId,
                              String symbol,
                              OrderSide side,
                              TriggerOrderType triggerType,
                              TriggerCondition triggerCondition,
                              long triggerPriceTicks,
                              OrderType orderType,
                              TimeInForce timeInForce,
                              long priceTicks,
                              long quantitySteps,
                              MarginMode marginMode,
                              PositionSide positionSide,
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
        this(triggerOrderId, ProductLine.LINEAR_PERPETUAL, userId, clientTriggerOrderId, ocoGroupId, symbol, side,
                triggerType, triggerCondition, triggerPriceTicks, null, null, null, null, null, orderType, timeInForce,
                priceTicks, quantitySteps, marginMode, positionSide, status, placedOrderId, triggerSequence,
                triggeredPriceTicks, rejectReason, traceId, expiresAt, triggeredAt, createdAt, updatedAt);
    }
}
