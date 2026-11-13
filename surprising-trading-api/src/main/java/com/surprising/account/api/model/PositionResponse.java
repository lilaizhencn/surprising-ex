package com.surprising.account.api.model;

import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.time.Instant;

public record PositionResponse(
        long userId,
        String instrumentId,
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
                            String instrumentId,
                            MarginMode marginMode,
                            long signedQuantitySteps,
                            long entryPriceTicks,
                            long realizedPnlUnits,
                            Instant updatedAt) {
        this(userId, instrumentId, marginMode, PositionSide.NET, signedQuantitySteps, entryPriceTicks,
                realizedPnlUnits, updatedAt);
    }

    public PositionResponse(long userId,
                            String instrumentId,
                            long signedQuantitySteps,
                            long entryPriceTicks,
                            long realizedPnlUnits,
                            Instant updatedAt) {
        this(userId, instrumentId, MarginMode.CROSS, PositionSide.NET, signedQuantitySteps, entryPriceTicks,
                realizedPnlUnits, updatedAt);
    }
}
