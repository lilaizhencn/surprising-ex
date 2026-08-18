package com.surprising.candlestick.api.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Realtime candle update emitted to Kafka after every accepted trade changes a candle.
 *
 * <p>WebSocket/fanout services should consume this event from the candle topic and push the
 * latest snapshot to subscribed clients. {@code status=PARTIAL} means the candle is still open;
 * {@code status=CLOSED} means the time bucket has ended.</p>
 */
public record CandleUpdatedEvent(
        String symbol,
        String period,
        Instant openTime,
        Instant closeTime,
        BigDecimal openPrice,
        BigDecimal highPrice,
        BigDecimal lowPrice,
        BigDecimal closePrice,
        BigDecimal baseVolume,
        BigDecimal quoteVolume,
        long tradeCount,
        String firstTradeId,
        String lastTradeId,
        Long firstSequence,
        Long lastSequence,
        CandleStatus status,
        Instant eventTime,
        Instant emittedAt,
        Integer sourcePartition,
        Long sourceOffset) {
}
