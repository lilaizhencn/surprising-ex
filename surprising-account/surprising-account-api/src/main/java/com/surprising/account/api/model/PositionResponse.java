package com.surprising.account.api.model;

import com.surprising.trading.api.model.MarginMode;
import java.time.Instant;

public record PositionResponse(
        long userId,
        String symbol,
        long instrumentVersion,
        MarginMode marginMode,
        long signedQuantitySteps,
        long entryPriceTicks,
        long realizedPnlUnits,
        Instant updatedAt) {

    public PositionResponse {
        marginMode = MarginMode.defaultIfNull(marginMode);
    }

    public PositionResponse(long userId,
                            String symbol,
                            long instrumentVersion,
                            long signedQuantitySteps,
                            long entryPriceTicks,
                            long realizedPnlUnits,
                            Instant updatedAt) {
        this(userId, symbol, instrumentVersion, MarginMode.CROSS, signedQuantitySteps, entryPriceTicks,
                realizedPnlUnits, updatedAt);
    }
}
