package com.surprising.trading.api.model;

import java.time.Instant;

public record EffectiveTradingFeeResponse(
        long userId,
        String symbol,
        long instrumentVersion,
        long makerFeeRatePpm,
        long takerFeeRatePpm,
        String source,
        Instant resolvedAt) {
}
