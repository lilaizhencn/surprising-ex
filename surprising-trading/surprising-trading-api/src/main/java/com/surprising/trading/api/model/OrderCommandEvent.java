package com.surprising.trading.api.model;

import java.time.Instant;

public record OrderCommandEvent(
        OrderCommandType commandType,
        long commandId,
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
        boolean reduceOnly,
        boolean postOnly,
        Instant commandTime,
        String traceId) {

    public OrderCommandEvent(OrderCommandType commandType,
                             long commandId,
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
                             boolean reduceOnly,
                             boolean postOnly,
                             Instant commandTime) {
        this(commandType, commandId, orderId, userId, clientOrderId, symbol, instrumentVersion, side, orderType,
                timeInForce, priceTicks, quantitySteps, reduceOnly, postOnly, commandTime, null);
    }
}
