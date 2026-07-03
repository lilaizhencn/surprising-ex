package com.surprising.account.api.model;

import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.time.Instant;

public record PositionMarginAdjustmentResponse(
        long userId,
        String symbol,
        String asset,
        MarginMode marginMode,
        PositionSide positionSide,
        long amountUnits,
        long positionMarginUnits,
        long availableUnits,
        long lockedUnits,
        long equityUnits,
        String referenceId,
        Instant updatedAt) {

    public PositionMarginAdjustmentResponse {
        marginMode = MarginMode.defaultIfNull(marginMode);
        positionSide = PositionSide.defaultIfNull(positionSide);
    }

    public PositionMarginAdjustmentResponse(long userId,
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
        this(userId, symbol, asset, marginMode, PositionSide.NET, amountUnits, positionMarginUnits, availableUnits,
                lockedUnits, equityUnits, referenceId, updatedAt);
    }
}
