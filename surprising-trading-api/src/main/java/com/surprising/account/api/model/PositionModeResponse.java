package com.surprising.account.api.model;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.PositionMode;
import java.time.Instant;
import java.util.Objects;

public record PositionModeResponse(
        ProductLine productLine,
        long userId,
        PositionMode positionMode,
        Instant updatedAt) {

    public PositionModeResponse {
        productLine = Objects.requireNonNull(productLine, "productLine");
        positionMode = PositionMode.defaultIfNull(positionMode);
    }
}
