package com.surprising.account.api.model;

import com.surprising.trading.api.model.MarginMode;
import java.time.Instant;

public record PositionMarginResponse(
        long userId,
        String symbol,
        String asset,
        MarginMode marginMode,
        long marginUnits,
        Instant updatedAt) {

    public PositionMarginResponse {
        marginMode = MarginMode.defaultIfNull(marginMode);
    }
}
