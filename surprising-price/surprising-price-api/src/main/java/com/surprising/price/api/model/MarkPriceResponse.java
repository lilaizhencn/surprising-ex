package com.surprising.price.api.model;

import java.math.BigDecimal;
import java.time.Instant;

public record MarkPriceResponse(
        String symbol,
        BigDecimal markPrice,
        long markPriceUnits,
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
