package com.surprising.trading.api.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record LeverageSettingRequest(
        @Positive long userId,
        @NotBlank @Size(max = 64) String symbol,
        MarginMode marginMode,
        @Positive long leveragePpm,
        @Size(max = 256) String reason) {

    public LeverageSettingRequest {
        marginMode = MarginMode.defaultIfNull(marginMode);
    }
}
