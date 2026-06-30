package com.surprising.price.api.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Fair mark price used by risk, liquidation, account equity, and WebSocket display.
 *
 * <p>The calculation follows the common exchange pattern: price1 reflects funding convergence,
 * price2 reflects smoothed book basis, and mark price is the median with last trade plus a final
 * clamp around the index price.</p>
 */
public record MarkPriceEvent(
        String symbol,
        BigDecimal markPrice,
        BigDecimal indexPrice,
        BigDecimal price1,
        BigDecimal price2,
        BigDecimal lastTradePrice,
        BigDecimal bestBidPrice,
        BigDecimal bestAskPrice,
        BigDecimal fundingRate,
        Instant nextFundingTime,
        long timeUntilFundingSeconds,
        BigDecimal basisAverage,
        long basisWindowSeconds,
        BigDecimal clampLow,
        BigDecimal clampHigh,
        long sequence,
        PriceStatus status,
        Instant eventTime) {
}
