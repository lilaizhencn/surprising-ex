package com.surprising.account.api.model;

import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.time.Instant;

public record PositionMarginResponse(
        long userId,
        String symbol,
        String asset,
        MarginMode marginMode,
        PositionSide positionSide,
        long marginUnits,
        Instant updatedAt) {

    public PositionMarginResponse {
        marginMode = MarginMode.defaultIfNull(marginMode);
        positionSide = PositionSide.defaultIfNull(positionSide);
    }

    public PositionMarginResponse(long userId,
                                  String symbol,
                                  String asset,
                                  MarginMode marginMode,
                                  long marginUnits,
                                  Instant updatedAt) {
        this(userId, symbol, asset, marginMode, PositionSide.NET, marginUnits, updatedAt);
    }
}
