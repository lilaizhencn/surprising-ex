package com.surprising.risk.provider.model;

import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;

/** Immutable account position input stored in Redis for mark-price-driven risk calculation. */
public record CachedRiskPosition(
        String symbol,
        MarginMode marginMode,
        PositionSide positionSide,
        long instrumentVersion,
        String settleAsset,
        long signedQuantitySteps,
        long entryPriceTicks,
        long positionMarginUnits) {

    public CachedRiskPosition {
        marginMode = MarginMode.defaultIfNull(marginMode);
        positionSide = PositionSide.defaultIfNull(positionSide);
        if (symbol == null || symbol.isBlank() || settleAsset == null || settleAsset.isBlank()) {
            throw new IllegalArgumentException("cached risk position symbol and settleAsset are required");
        }
        if (instrumentVersion <= 0L || signedQuantitySteps == 0L || entryPriceTicks <= 0L
                || positionMarginUnits < 0L) {
            throw new IllegalArgumentException("invalid cached risk position fixed-point fields");
        }
    }
}
