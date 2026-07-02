package com.surprising.account.api.model;

import com.surprising.trading.api.model.MarginMode;
import java.time.Instant;

public record PositionMarginAdjustmentResponse(
        long userId,
        String symbol,
        String asset,
        MarginMode marginMode,
        long amountUnits,
        long positionMarginUnits,
        long availableUnits,
        long lockedUnits,
        long equityUnits,
        String referenceId,
        Instant updatedAt) {

    public PositionMarginAdjustmentResponse {
        marginMode = MarginMode.defaultIfNull(marginMode);
    }
}
