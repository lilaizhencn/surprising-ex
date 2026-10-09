package com.surprising.candlestick.provider.aggregation;

import com.surprising.candlestick.api.model.CandlePeriod;
import java.time.Instant;
import java.util.Locale;

/**
 * Canonical key for one candle row/state entry.
 *
 * <p>The same key format is used across RocksDB stores so a candle can be updated in memory,
 * marked dirty for PostgreSQL flushing, and emitted as a realtime update without dynamic table
 * names.</p>
 */
public record CandleKey(String instrumentId, CandlePeriod period, Instant openTime) {

    /**
     * Compact string key for Kafka Streams key-value state stores.
     */
    public String value() {
        return normalizeSymbol(instrumentId) + "|" + period.code() + "|" + openTime.toEpochMilli();
    }

    public static String traceId(com.surprising.product.api.ProductLine product,
                                 com.surprising.candlestick.api.model.CandleUpdatedEvent candle) {
        return traceId(product, candle.instrumentId(), candle.period(), candle.openTime());
    }

    public static String traceId(com.surprising.product.api.ProductLine product, String instrumentId,
                                 String period, Instant openTime) {
        return "candle-" + product + "-" + instrumentId + "-" + period + "-" + openTime.toEpochMilli();
    }

    public static CandleKey of(String instrumentId, CandlePeriod period, Instant openTime) {
        return new CandleKey(normalizeSymbol(instrumentId), period, openTime);
    }

    /**
     * Normalizes and validates symbols before they are used in Kafka state keys or SQL filters.
     */
    public static String normalizeSymbol(String instrumentId) {
        if (instrumentId == null) {
            throw new IllegalArgumentException("instrumentId is required");
        }
        String normalized = instrumentId.trim().toUpperCase(Locale.ROOT);
        if (!com.surprising.product.api.InstrumentIds.valid(normalized)) {
            throw new IllegalArgumentException("Invalid instrumentId: " + instrumentId);
        }
        return normalized;
    }
}
