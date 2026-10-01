package com.surprising.trading.api.model;

import com.surprising.product.api.ProductLine;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record LeverageSettingRequest(
        @Positive long userId,
        ProductLine productLine,
        @NotBlank @Size(max = 64) String instrumentId,
        MarginMode marginMode,
        @Positive long leveragePpm,
        @Size(max = 256) String reason,
        Boolean repriceCrossMargin) {

    public LeverageSettingRequest(long userId, ProductLine productLine, String instrumentId,
            MarginMode marginMode, long leveragePpm, String reason) {
        this(userId, productLine, instrumentId, marginMode, leveragePpm, reason, false);
    }

    public LeverageSettingRequest {
        marginMode = MarginMode.defaultIfNull(marginMode);
        repriceCrossMargin = Boolean.TRUE.equals(repriceCrossMargin);
    }

}
