package com.surprising.trading.api.model;

import java.math.BigDecimal;
import java.time.Instant;

public record MarketTickerSummary(
        String symbol,
        long firstTradeId,
        long lastTradeId,
        long tradeCount,
        long openPriceTicks,
        long highPriceTicks,
        long lowPriceTicks,
        long lastPriceTicks,
        BigDecimal volumeSteps,
        BigDecimal quoteVolumeTicksSteps,
        BigDecimal lastQuantitySteps,
        Instant openTime,
        Instant closeTime) {

    public MarketTickerSummary {
        volumeSteps = volumeSteps == null ? BigDecimal.ZERO : volumeSteps;
        quoteVolumeTicksSteps = quoteVolumeTicksSteps == null ? BigDecimal.ZERO : quoteVolumeTicksSteps;
        lastQuantitySteps = lastQuantitySteps == null ? BigDecimal.ZERO : lastQuantitySteps;
    }
}
