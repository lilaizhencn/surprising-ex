package com.surprising.account.api.model;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.PositionMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record PositionModeUpdateRequest(
        @Positive long userId,
        ProductLine productLine,
        @NotNull PositionMode positionMode,
        @NotBlank @Size(max = 128) String referenceId) {

    public PositionModeUpdateRequest {
        positionMode = PositionMode.defaultIfNull(positionMode);
    }
}
