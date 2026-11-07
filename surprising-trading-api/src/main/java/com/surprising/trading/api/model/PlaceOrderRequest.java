package com.surprising.trading.api.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record PlaceOrderRequest(
        @Positive long userId,
        @NotBlank @Size(max = 64) String clientOrderId,
        @NotBlank @Size(max = 64) String instrumentId,
        @NotNull OrderSide side,
        @NotNull OrderType orderType,
        @NotNull TimeInForce timeInForce,
        @Min(0) long priceTicks,
        @Positive long quantitySteps,
        MarginMode marginMode,
        PositionSide positionSide,
        boolean reduceOnly,
        boolean postOnly,
        BboPriceMode bboPriceMode,
        @Min(0) long takeProfitPriceTicks,
        @Min(0) long stopLossPriceTicks,
        TriggerPriceSource tpSlPriceSource,
        SelfTradePreventionMode stpMode) {

    public PlaceOrderRequest {
        marginMode = MarginMode.defaultIfNull(marginMode);
        positionSide = PositionSide.defaultIfNull(positionSide);
        tpSlPriceSource = tpSlPriceSource != null ? tpSlPriceSource : TriggerPriceSource.MARK;
        stpMode = SelfTradePreventionMode.defaultIfNull(stpMode);
    }

    public PlaceOrderRequest(long userId, String clientOrderId, String instrumentId, OrderSide side,
                             OrderType orderType, TimeInForce timeInForce, long priceTicks,
                             long quantitySteps, MarginMode marginMode, PositionSide positionSide,
                             boolean reduceOnly, boolean postOnly, BboPriceMode bboPriceMode) {
        this(userId, clientOrderId, instrumentId, side, orderType, timeInForce, priceTicks, quantitySteps,
                marginMode, positionSide, reduceOnly, postOnly, bboPriceMode, 0L, 0L, TriggerPriceSource.MARK, SelfTradePreventionMode.NONE);
    }

    public PlaceOrderRequest(long userId, String clientOrderId, String instrumentId, OrderSide side,
                             OrderType orderType, TimeInForce timeInForce, long priceTicks,
                             long quantitySteps, MarginMode marginMode, PositionSide positionSide,
                             boolean reduceOnly, boolean postOnly) {
        this(userId, clientOrderId, instrumentId, side, orderType, timeInForce, priceTicks, quantitySteps,
                marginMode, positionSide, reduceOnly, postOnly, null, 0L, 0L, TriggerPriceSource.MARK, SelfTradePreventionMode.NONE);
    }

    public PlaceOrderRequest(long userId,
                             String clientOrderId,
                             String instrumentId,
                             OrderSide side,
                             OrderType orderType,
                             TimeInForce timeInForce,
                             long priceTicks,
                             long quantitySteps,
                             MarginMode marginMode,
                             boolean reduceOnly,
                             boolean postOnly) {
        this(userId, clientOrderId, instrumentId, side, orderType, timeInForce, priceTicks, quantitySteps,
                marginMode, PositionSide.NET, reduceOnly, postOnly);
    }

    public PlaceOrderRequest(long userId,
                             String clientOrderId,
                             String instrumentId,
                             OrderSide side,
                             OrderType orderType,
                             TimeInForce timeInForce,
                             long priceTicks,
                             long quantitySteps,
                             boolean reduceOnly,
                             boolean postOnly) {
        this(userId, clientOrderId, instrumentId, side, orderType, timeInForce, priceTicks, quantitySteps,
                MarginMode.CROSS, PositionSide.NET, reduceOnly, postOnly);
    }

    public boolean hasBracketOrders() {
        return takeProfitPriceTicks > 0 || stopLossPriceTicks > 0;
    }
}
