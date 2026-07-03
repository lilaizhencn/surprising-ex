package com.surprising.account.api.model;

import com.surprising.trading.api.model.PositionMode;
import java.time.Instant;

public record PositionModeResponse(
        long userId,
        PositionMode positionMode,
        Instant updatedAt) {

    public PositionModeResponse {
        positionMode = PositionMode.defaultIfNull(positionMode);
    }
}
