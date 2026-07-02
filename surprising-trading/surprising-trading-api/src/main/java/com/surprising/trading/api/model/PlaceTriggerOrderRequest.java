package com.surprising.trading.api.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record PlaceTriggerOrderRequest(
        @Positive long userId,
        @Size(max = 64) String clientTriggerOrderId,
        @Size(max = 64) String ocoGroupId,
        @NotBlank @Size(max = 64) String symbol,
        @NotNull OrderSide side,
        @NotNull TriggerOrderType triggerType,
        TriggerPriceType triggerPriceType,
        @Positive long triggerPriceTicks,
        @NotNull OrderType orderType,
        @NotNull TimeInForce timeInForce,
        @Min(0) long priceTicks,
        @Positive long quantitySteps,
        MarginMode marginMode,
        PositionSide positionSide,
        Instant expiresAt) {

    public PlaceTriggerOrderRequest {
        triggerPriceType = triggerPriceType == null ? TriggerPriceType.MARK_PRICE : triggerPriceType;
        marginMode = MarginMode.defaultIfNull(marginMode);
        positionSide = PositionSide.defaultIfNull(positionSide);
    }

    public PlaceTriggerOrderRequest(long userId,
                                    String clientTriggerOrderId,
                                    String ocoGroupId,
                                    String symbol,
                                    OrderSide side,
                                    TriggerOrderType triggerType,
                                    TriggerPriceType triggerPriceType,
                                    long triggerPriceTicks,
                                    OrderType orderType,
                                    TimeInForce timeInForce,
                                    long priceTicks,
                                    long quantitySteps,
                                    MarginMode marginMode,
                                    Instant expiresAt) {
        this(userId, clientTriggerOrderId, ocoGroupId, symbol, side, triggerType, triggerPriceType,
                triggerPriceTicks, orderType, timeInForce, priceTicks, quantitySteps, marginMode, PositionSide.NET,
                expiresAt);
    }
}
