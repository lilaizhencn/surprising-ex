package com.surprising.trading.order.model;

import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.TimeInForce;
import java.time.Instant;

public record OrderRecord(
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
        long makerFeeRatePpm,
        long takerFeeRatePpm,
        boolean reduceOnly,
        boolean postOnly,
        OrderStatus status,
        String rejectReason,
        Instant createdAt,
        Instant updatedAt) {

    public OrderRecord {
        marginMode = MarginMode.defaultIfNull(marginMode);
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
        this(orderId, userId, clientOrderId, symbol, instrumentVersion, side, orderType, timeInForce,
                priceTicks, quantitySteps, executedQuantitySteps, remainingQuantitySteps, MarginMode.CROSS,
                makerFeeRatePpm, takerFeeRatePpm, reduceOnly, postOnly, status, rejectReason, createdAt, updatedAt);
    }
}
