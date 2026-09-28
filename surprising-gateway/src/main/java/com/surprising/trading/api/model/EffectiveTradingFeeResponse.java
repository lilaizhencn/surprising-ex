package com.surprising.trading.api.model;

import com.surprising.product.api.ProductLine;
import java.time.Instant;

public record EffectiveTradingFeeResponse(
        long userId,
        ProductLine productLine,
        String instrumentId,
        long instrumentChangeId,
        long makerFeeRatePpm,
        long takerFeeRatePpm,
        String source,
        Instant resolvedAt) {

}
