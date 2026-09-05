package com.surprising.candlestick.api.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Executed perpetual trade produced by the matching/trading service.
 *
 * <p>The Kafka record key must be the normalized {@code symbol}. That keeps all trades for
 * one symbol in one Kafka partition, which is the concurrency boundary for candle aggregation.
 * {@code tradeId} and {@code sequence} are both carried so the consumer can reject duplicates
 * and old replayed trades after restarts or Kafka redelivery.</p>
 */
public record TradeEvent(
        @NotBlank String symbol,
        @NotBlank String tradeId,
        @PositiveOrZero long sequence,
        @NotNull Instant tradeTime,
        @NotNull @Positive BigDecimal price,
        @NotNull @Positive BigDecimal quantity,
        @NotNull TradeSide side,
        String makerOrderId,
        String takerOrderId) {

    /**
     * Stable idempotency key used by the RocksDB dedupe state store.
     */
    public String idempotencyKey() {
        if (tradeId != null && !tradeId.isBlank()) {
            return "trade:" + tradeId.trim();
        }
        return "sequence:" + sequence;
    }
}
