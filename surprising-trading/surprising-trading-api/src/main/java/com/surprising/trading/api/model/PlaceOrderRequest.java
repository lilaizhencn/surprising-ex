package com.surprising.trading.api.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record PlaceOrderRequest(
        @Positive long userId,
        @Size(max = 64) String clientOrderId,
        @NotBlank @Size(max = 64) String symbol,
        @NotNull OrderSide side,
        @NotNull OrderType orderType,
        @NotNull TimeInForce timeInForce,
        @Min(0) long priceTicks,
        @Positive long quantitySteps,
        MarginMode marginMode,
        PositionSide positionSide,
        boolean reduceOnly,
        boolean postOnly) {

    public PlaceOrderRequest {
        marginMode = MarginMode.defaultIfNull(marginMode);
        positionSide = PositionSide.defaultIfNull(positionSide);
    }

    public PlaceOrderRequest(long userId,
                             String clientOrderId,
                             String symbol,
                             OrderSide side,
                             OrderType orderType,
                             TimeInForce timeInForce,
                             long priceTicks,
                             long quantitySteps,
                             MarginMode marginMode,
                             boolean reduceOnly,
                             boolean postOnly) {
        this(userId, clientOrderId, symbol, side, orderType, timeInForce, priceTicks, quantitySteps,
                marginMode, PositionSide.NET, reduceOnly, postOnly);
    }

    public PlaceOrderRequest(long userId,
                             String clientOrderId,
                             String symbol,
                             OrderSide side,
                             OrderType orderType,
                             TimeInForce timeInForce,
                             long priceTicks,
                             long quantitySteps,
                             boolean reduceOnly,
                             boolean postOnly) {
        this(userId, clientOrderId, symbol, side, orderType, timeInForce, priceTicks, quantitySteps,
                MarginMode.CROSS, PositionSide.NET, reduceOnly, postOnly);
    }
}
