package com.surprising.account.api.model;

import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.time.Instant;

public record PositionResponse(
        long userId,
        String symbol,
        long instrumentChangeId,
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
                            long instrumentChangeId,
                            MarginMode marginMode,
                            long signedQuantitySteps,
                            long entryPriceTicks,
                            long realizedPnlUnits,
                            Instant updatedAt) {
        this(userId, symbol, instrumentChangeId, marginMode, PositionSide.NET, signedQuantitySteps, entryPriceTicks,
                realizedPnlUnits, updatedAt);
    }

    public PositionResponse(long userId,
                            String symbol,
                            long instrumentChangeId,
                            long signedQuantitySteps,
                            long entryPriceTicks,
                            long realizedPnlUnits,
                            Instant updatedAt) {
        this(userId, symbol, instrumentChangeId, MarginMode.CROSS, PositionSide.NET, signedQuantitySteps, entryPriceTicks,
                realizedPnlUnits, updatedAt);
    }
}
