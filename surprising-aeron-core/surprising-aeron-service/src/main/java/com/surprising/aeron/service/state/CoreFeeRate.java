package com.surprising.aeron.service.state;

public record CoreFeeRate(long makerFeeRatePpm, long takerFeeRatePpm, long policyVersion) {
    public CoreFeeRate {
        if (makerFeeRatePpm < -1_000_000 || makerFeeRatePpm > 1_000_000
                || takerFeeRatePpm < -1_000_000 || takerFeeRatePpm > 1_000_000
                || makerFeeRatePpm > takerFeeRatePpm || policyVersion < 0) {
            throw new IllegalArgumentException("invalid resolved fee rate");
        }
    }
}
