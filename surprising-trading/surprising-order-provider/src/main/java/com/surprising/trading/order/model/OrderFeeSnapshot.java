package com.surprising.trading.order.model;

import com.surprising.product.api.ProductLine;

public record OrderFeeSnapshot(
        ProductLine productLine,
        long makerFeeRatePpm,
        long takerFeeRatePpm,
        String source) {

    private static final long MAX_ABS_FEE_RATE_PPM = 1_000_000L;

    public OrderFeeSnapshot {
        productLine = productLine == null ? ProductLine.LINEAR_PERPETUAL : productLine;
        if (makerFeeRatePpm < -MAX_ABS_FEE_RATE_PPM || makerFeeRatePpm > MAX_ABS_FEE_RATE_PPM
                || takerFeeRatePpm < -MAX_ABS_FEE_RATE_PPM || takerFeeRatePpm > MAX_ABS_FEE_RATE_PPM) {
            throw new IllegalArgumentException("fee rates must be within +/- 100%");
        }
        source = source == null || source.isBlank() ? "INSTRUMENT" : source.trim();
    }

    public OrderFeeSnapshot(long makerFeeRatePpm, long takerFeeRatePpm, String source) {
        this(ProductLine.LINEAR_PERPETUAL, makerFeeRatePpm, takerFeeRatePpm, source);
    }
}
