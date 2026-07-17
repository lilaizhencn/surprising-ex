package com.surprising.account.provider.model;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.time.Instant;

public record CachedPosition(
        ProductLine productLine,
        long userId,
        String symbol,
        Long instrumentVersion,
        MarginMode marginMode,
        PositionSide positionSide,
        long signedQuantitySteps,
        long entryPriceTicks,
        long entryValueTicks,
        long realizedPnlUnits,
        Instant updatedAt,
        long revision) {
}
