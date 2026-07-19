package com.surprising.account.api.model;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.time.Instant;

/**
 * Durable, complete position state emitted by the account single writer.
 *
 * <p>The same event drives risk, trigger cleanup, WebSocket fanout, and the Redis position read model.
 * PostgreSQL allocates {@code revision}; Redis applies it with compare-and-set semantics. Producers must use
 * {@link #partitionKey()} as the Kafka key so every position update for one user remains ordered.</p>
 */
public record PositionUpdatedEvent(
        int schemaVersion,
        long eventId,
        long tradeId,
        ProductLine productLine,
        long revision,
        long userId,
        String symbol,
        long instrumentVersion,
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
        Instant eventTime,
        String traceId) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public PositionUpdatedEvent {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported position event schemaVersion: " + schemaVersion);
        }
        if (eventId <= 0L || revision <= 0L || instrumentVersion <= 0L) {
            throw new IllegalArgumentException("eventId, revision, and instrumentVersion must be positive");
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
        marginMode = MarginMode.defaultIfNull(marginMode);
        positionSide = PositionSide.defaultIfNull(positionSide);
        marginAsset = marginAsset == null ? "" : marginAsset;
        if (marginUnits < 0L) {
            throw new IllegalArgumentException("marginUnits must not be negative");
        }
        if (positionUpdatedAt == null || marginUpdatedAt == null || eventTime == null) {
            throw new IllegalArgumentException("position, margin, and event timestamps are required");
        }
    }

    public String partitionKey() {
        return AccountUserCommand.partitionKey(productLine, userId);
    }

    public PositionCacheEvent cacheEvent() {
        return new PositionCacheEvent(
                revision,
                productLine,
                userId,
                symbol,
                instrumentVersion,
                marginMode,
                positionSide,
                signedQuantitySteps,
                entryPriceTicks,
                entryValueTicks,
                realizedPnlUnits,
                marginAsset,
                marginUnits,
                positionUpdatedAt,
                marginUpdatedAt,
                revision);
    }

    /**
     * Convenience constructor for in-process tests and callers that do not need the projection-only fields.
     * Production publication always uses the complete canonical constructor.
     */
    public PositionUpdatedEvent(long eventId,
                                long tradeId,
                                long userId,
                                String symbol,
                                long instrumentVersion,
                                MarginMode marginMode,
                                PositionSide positionSide,
                                long signedQuantitySteps,
                                long entryPriceTicks,
                                long realizedPnlUnits,
                                Instant eventTime,
                                String traceId) {
        this(CURRENT_SCHEMA_VERSION, eventId, tradeId, ProductLine.LINEAR_PERPETUAL, eventId, userId, symbol,
                instrumentVersion, marginMode, positionSide, signedQuantitySteps, entryPriceTicks, 0L,
                realizedPnlUnits, "", 0L, eventTime, eventTime, eventTime, traceId);
    }

    public PositionUpdatedEvent(long eventId,
                                long tradeId,
                                long userId,
                                String symbol,
                                long instrumentVersion,
                                MarginMode marginMode,
                                long signedQuantitySteps,
                                long entryPriceTicks,
                                long realizedPnlUnits,
                                Instant eventTime,
                                String traceId) {
        this(eventId, tradeId, userId, symbol, instrumentVersion, marginMode, PositionSide.NET,
                signedQuantitySteps, entryPriceTicks, realizedPnlUnits, eventTime, traceId);
    }

    public PositionUpdatedEvent(long eventId,
                                long tradeId,
                                long userId,
                                String symbol,
                                long instrumentVersion,
                                long signedQuantitySteps,
                                long entryPriceTicks,
                                long realizedPnlUnits,
                                Instant eventTime) {
        this(eventId, tradeId, userId, symbol, instrumentVersion, signedQuantitySteps, entryPriceTicks,
                realizedPnlUnits, eventTime, null);
    }

    public PositionUpdatedEvent(long eventId,
                                long tradeId,
                                long userId,
                                String symbol,
                                long instrumentVersion,
                                long signedQuantitySteps,
                                long entryPriceTicks,
                                long realizedPnlUnits,
                                Instant eventTime,
                                String traceId) {
        this(eventId, tradeId, userId, symbol, instrumentVersion, MarginMode.CROSS, PositionSide.NET,
                signedQuantitySteps, entryPriceTicks, realizedPnlUnits, eventTime, traceId);
    }
}
