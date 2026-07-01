package com.surprising.trading.api.model;

import java.time.Instant;

public record LeverageSettingResponse(
        long userId,
        String symbol,
        MarginMode marginMode,
        long leveragePpm,
        long maxLeveragePpm,
        long initialMarginRatePpm,
        String source,
        Instant updatedAt) {

    public LeverageSettingResponse {
        marginMode = MarginMode.defaultIfNull(marginMode);
    }
}
