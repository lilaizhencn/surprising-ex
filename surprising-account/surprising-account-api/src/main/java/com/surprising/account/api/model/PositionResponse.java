package com.surprising.account.api.model;

import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.time.Instant;

public record PositionResponse(
        long userId,
        String symbol,
        long instrumentVersion,
        MarginMode marginMode,
        PositionSide positionSide,
        long signedQuantitySteps,
        long entryPriceTicks,
        long realizedPnlUnits,
        Instant updatedAt) {

    public PositionResponse {
        marginMode = MarginMode.defaultIfNull(marginMode);
        positionSide = PositionSide.defaultIfNull(positionSide);
    }

    public PositionResponse(long userId,
                            String symbol,
                            long instrumentVersion,
                            MarginMode marginMode,
                            long signedQuantitySteps,
                            long entryPriceTicks,
                            long realizedPnlUnits,
                            Instant updatedAt) {
        this(userId, symbol, instrumentVersion, marginMode, PositionSide.NET, signedQuantitySteps, entryPriceTicks,
                realizedPnlUnits, updatedAt);
    }

    public PositionResponse(long userId,
                            String symbol,
                            long instrumentVersion,
                            long signedQuantitySteps,
                            long entryPriceTicks,
                            long realizedPnlUnits,
                            Instant updatedAt) {
        this(userId, symbol, instrumentVersion, MarginMode.CROSS, PositionSide.NET, signedQuantitySteps, entryPriceTicks,
                realizedPnlUnits, updatedAt);
    }
}
