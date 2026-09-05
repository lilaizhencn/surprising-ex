package com.surprising.trading.order.model;

import com.surprising.product.api.ProductLine;
import java.util.Objects;

public record OrderFeeSnapshot(
        ProductLine productLine,
        long makerFeeRatePpm,
        long takerFeeRatePpm,
        String source) {

    private static final long MAX_ABS_FEE_RATE_PPM = 1_000_000L;

    public OrderFeeSnapshot {
        productLine = Objects.requireNonNull(productLine, "productLine");
        if (makerFeeRatePpm < -MAX_ABS_FEE_RATE_PPM || makerFeeRatePpm > MAX_ABS_FEE_RATE_PPM
                || takerFeeRatePpm < -MAX_ABS_FEE_RATE_PPM || takerFeeRatePpm > MAX_ABS_FEE_RATE_PPM) {
            throw new IllegalArgumentException("fee rates must be within +/- 100%");
        }
        source = source == null || source.isBlank() ? "INSTRUMENT" : source.trim();
    }

}
