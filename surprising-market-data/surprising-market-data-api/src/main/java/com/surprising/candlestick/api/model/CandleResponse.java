package com.surprising.candlestick.api.model;

import java.math.BigDecimal;
import java.time.Instant;

public record CandleResponse(
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
        Instant updatedAt) {
}
