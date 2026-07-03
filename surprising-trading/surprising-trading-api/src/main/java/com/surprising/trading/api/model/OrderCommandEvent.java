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
        MarginMode marginMode,
        PositionSide positionSide,
        boolean reduceOnly,
        boolean postOnly,
        Instant commandTime,
        String traceId) {

    public OrderCommandEvent {
        marginMode = MarginMode.defaultIfNull(marginMode);
        positionSide = PositionSide.defaultIfNull(positionSide);
    }

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
                             MarginMode marginMode,
                             boolean reduceOnly,
                             boolean postOnly,
                             Instant commandTime,
                             String traceId) {
        this(commandType, commandId, orderId, userId, clientOrderId, symbol, instrumentVersion, side, orderType,
                timeInForce, priceTicks, quantitySteps, marginMode, PositionSide.NET, reduceOnly, postOnly,
                commandTime, traceId);
    }

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
                timeInForce, priceTicks, quantitySteps, MarginMode.CROSS, PositionSide.NET, reduceOnly, postOnly,
                commandTime, null);
    }

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
                             Instant commandTime,
                             String traceId) {
        this(commandType, commandId, orderId, userId, clientOrderId, symbol, instrumentVersion, side, orderType,
                timeInForce, priceTicks, quantitySteps, MarginMode.CROSS, PositionSide.NET, reduceOnly, postOnly,
                commandTime, traceId);
    }
}
