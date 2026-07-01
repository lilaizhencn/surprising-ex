package com.surprising.account.provider.model;

public record OrderFeeSnapshot(
        long makerFeeRatePpm,
        long takerFeeRatePpm) {

    private static final long MAX_ABS_FEE_RATE_PPM = 1_000_000L;

    public OrderFeeSnapshot {
        if (makerFeeRatePpm < -MAX_ABS_FEE_RATE_PPM || makerFeeRatePpm > MAX_ABS_FEE_RATE_PPM
                || takerFeeRatePpm < -MAX_ABS_FEE_RATE_PPM || takerFeeRatePpm > MAX_ABS_FEE_RATE_PPM) {
            throw new IllegalArgumentException("fee rates must be within +/- 100%");
        }
    }
}
