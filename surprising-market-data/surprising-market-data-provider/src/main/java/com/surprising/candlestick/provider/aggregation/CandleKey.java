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
public record CandleKey(String symbol, CandlePeriod period, Instant openTime) {

    /**
     * Compact string key for Kafka Streams key-value state stores.
     */
    public String value() {
        return normalizeSymbol(symbol) + "|" + period.code() + "|" + openTime.toEpochMilli();
    }

    public static CandleKey of(String symbol, CandlePeriod period, Instant openTime) {
        return new CandleKey(normalizeSymbol(symbol), period, openTime);
    }

    /**
     * Normalizes and validates symbols before they are used in Kafka state keys or SQL filters.
     */
    public static String normalizeSymbol(String symbol) {
        if (symbol == null) {
            throw new IllegalArgumentException("symbol is required");
        }
        String normalized = symbol.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9][A-Z0-9_-]{1,63}")) {
            throw new IllegalArgumentException("Invalid symbol: " + symbol);
        }
        return normalized;
    }
}
