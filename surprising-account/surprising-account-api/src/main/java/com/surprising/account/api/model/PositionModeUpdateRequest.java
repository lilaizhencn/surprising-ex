package com.surprising.account.api.model;

import com.surprising.trading.api.model.PositionMode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record PositionModeUpdateRequest(
        @Positive long userId,
        @NotNull PositionMode positionMode) {

    public PositionModeUpdateRequest {
        positionMode = PositionMode.defaultIfNull(positionMode);
    }
}
