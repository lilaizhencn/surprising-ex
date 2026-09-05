package com.surprising.price.api.model;

import com.surprising.product.api.ProductLine;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Fair mark price used by risk, liquidation, account equity, and WebSocket display.
 *
 * <p>The perpetual calculation uses price1 for funding convergence and price2 for smoothed book
 * basis. Options must additionally provide {@code sameExpiryForwardPrice}; it is a distinct risk
 * input and must never be synthesized from price1.</p>
 */
public record MarkPriceEvent(
        ProductLine productLine,
        String symbol,
        long instrumentVersion,
        long markPriceUnits,
        long markPriceTicks,
        BigDecimal markPrice,
        BigDecimal indexPrice,
        BigDecimal sameExpiryForwardPrice,
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
        Instant eventTime,
        Instant publishedAt) {
}
