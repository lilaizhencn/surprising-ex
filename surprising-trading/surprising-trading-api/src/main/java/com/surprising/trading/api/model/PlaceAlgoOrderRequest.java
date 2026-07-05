package com.surprising.trading.api.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record PlaceAlgoOrderRequest(
        @Positive long userId,
        @Size(max = 64) String clientAlgoOrderId,
        @NotBlank @Size(max = 64) String symbol,
        @NotNull AlgoOrderType algoType,
        @NotNull OrderSide side,
        @Min(0) long priceTicks,
        @Positive long quantitySteps,
        @Positive long childQuantitySteps,
        @Min(1) @Max(86_400) long intervalSeconds,
        @Min(1) @Max(86_400) long durationSeconds,
        MarginMode marginMode,
        PositionSide positionSide,
        boolean reduceOnly,
        boolean postOnly,
        TimeInForce timeInForce,
        Instant startAt) {

    public PlaceAlgoOrderRequest {
        marginMode = MarginMode.defaultIfNull(marginMode);
        positionSide = PositionSide.defaultIfNull(positionSide);
    }
}
