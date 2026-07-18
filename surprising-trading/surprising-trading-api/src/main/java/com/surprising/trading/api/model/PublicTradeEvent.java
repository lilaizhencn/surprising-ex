package com.surprising.trading.api.model;

import java.time.Instant;

/** Public tick trade emitted directly from the matcher without account or database fields. */
public record PublicTradeEvent(
        String tradeId,
        long sequence,
        String symbol,
        long instrumentVersion,
        OrderSide takerSide,
        long priceTicks,
        long quantitySteps,
        Instant eventTime,
        String traceId) {
}
