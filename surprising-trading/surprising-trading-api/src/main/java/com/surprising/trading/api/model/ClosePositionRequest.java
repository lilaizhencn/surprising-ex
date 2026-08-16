package com.surprising.trading.api.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record ClosePositionRequest(
        @Positive long userId,
        @NotBlank @Size(max = 64) String clientOrderId,
        @Size(max = 64) String symbol,
        MarginMode marginMode,
        PositionSide positionSide) {

    public ClosePositionRequest {
        marginMode = MarginMode.defaultIfNull(marginMode);
        positionSide = PositionSide.defaultIfNull(positionSide);
    }
}
