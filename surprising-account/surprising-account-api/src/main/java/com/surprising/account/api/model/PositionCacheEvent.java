package com.surprising.account.api.model;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.time.Instant;

/**
 * Complete, revisioned projection of one position and its collateral for Redis.
 *
 * <p>The revision is allocated by PostgreSQL and is globally increasing. Redis applies this event
 * with compare-and-set semantics, so retries and out-of-order delivery cannot overwrite newer state.</p>
 */
public record PositionCacheEvent(
        long eventId,
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
        String marginAsset,
        long marginUnits,
        Instant positionUpdatedAt,
        Instant marginUpdatedAt,
        long revision) {

    public PositionCacheEvent {
        if (eventId <= 0L || revision <= 0L || eventId != revision) {
            throw new IllegalArgumentException("position cache eventId and revision must be equal and positive");
        }
        if (productLine == null) {
            throw new IllegalArgumentException("productLine is required");
        }
        if (userId <= 0L) {
            throw new IllegalArgumentException("userId must be positive");
        }
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol is required");
        }
        if (instrumentVersion == null || instrumentVersion <= 0L) {
            throw new IllegalArgumentException("instrumentVersion must be positive");
        }
        marginMode = MarginMode.defaultIfNull(marginMode);
        positionSide = PositionSide.defaultIfNull(positionSide);
        marginAsset = marginAsset == null ? "" : marginAsset;
        if (marginUnits < 0L) {
            throw new IllegalArgumentException("marginUnits must not be negative");
        }
        if (positionUpdatedAt == null || marginUpdatedAt == null) {
            throw new IllegalArgumentException("position and margin timestamps are required");
        }
    }
}
