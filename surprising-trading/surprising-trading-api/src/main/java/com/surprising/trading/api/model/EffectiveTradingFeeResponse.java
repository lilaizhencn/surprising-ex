package com.surprising.trading.api.model;

import com.surprising.product.api.ProductLine;
import java.time.Instant;

public record EffectiveTradingFeeResponse(
        long userId,
        ProductLine productLine,
        String symbol,
        long instrumentVersion,
        long makerFeeRatePpm,
        long takerFeeRatePpm,
        String source,
        Instant resolvedAt) {

    public EffectiveTradingFeeResponse(long userId,
                                       String symbol,
                                       long instrumentVersion,
                                       long makerFeeRatePpm,
                                       long takerFeeRatePpm,
                                       String source,
                                       Instant resolvedAt) {
        this(userId, ProductLine.LINEAR_PERPETUAL, symbol, instrumentVersion, makerFeeRatePpm,
                takerFeeRatePpm, source, resolvedAt);
    }
}
