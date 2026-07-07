package com.surprising.account.api.model;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.PositionMode;
import java.time.Instant;

public record PositionModeResponse(
        ProductLine productLine,
        long userId,
        PositionMode positionMode,
        Instant updatedAt) {

    public PositionModeResponse {
        productLine = productLine == null ? ProductLine.LINEAR_PERPETUAL : productLine;
        positionMode = PositionMode.defaultIfNull(positionMode);
    }

    public PositionModeResponse(long userId, PositionMode positionMode, Instant updatedAt) {
        this(ProductLine.LINEAR_PERPETUAL, userId, positionMode, updatedAt);
    }
}
