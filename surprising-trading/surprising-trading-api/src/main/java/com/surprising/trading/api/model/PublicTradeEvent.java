package com.surprising.trading.api.model;

import java.time.Instant;

/** Public trade from committed business replay; no private account fields. Sequence is monotonic per product exporter. */
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
