package com.surprising.account.provider.model;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.time.Instant;

public record CachedPositionMargin(
        ProductLine productLine,
        long userId,
        String symbol,
        String asset,
        MarginMode marginMode,
        PositionSide positionSide,
        long marginUnits,
        Instant updatedAt,
        long revision) {
}
