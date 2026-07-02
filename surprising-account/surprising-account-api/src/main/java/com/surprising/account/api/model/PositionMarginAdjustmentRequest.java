package com.surprising.account.api.model;

import com.surprising.trading.api.model.MarginMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record PositionMarginAdjustmentRequest(
        @Positive long userId,
        @NotBlank @Size(max = 64) String symbol,
        MarginMode marginMode,
        long amountUnits,
        @NotBlank @Size(max = 128) String referenceId,
        @Size(max = 128) String reason) {

    public PositionMarginAdjustmentRequest {
        marginMode = MarginMode.defaultIfNull(marginMode);
    }
}
