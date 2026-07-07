package com.surprising.account.api.model;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.PositionMode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record PositionModeUpdateRequest(
        @Positive long userId,
        ProductLine productLine,
        @NotNull PositionMode positionMode) {

    public PositionModeUpdateRequest {
        positionMode = PositionMode.defaultIfNull(positionMode);
    }

    public PositionModeUpdateRequest(long userId, PositionMode positionMode) {
        this(userId, ProductLine.LINEAR_PERPETUAL, positionMode);
    }
}
