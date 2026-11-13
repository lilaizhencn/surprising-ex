package com.surprising.funding.provider.service;

public final class FundingMath {

    public static final long RATE_SCALE = 1_000_000L;

    private FundingMath() {
    }

    public static long clampRate(long rawRatePpm, long floorPpm, long capPpm) {
        return Math.max(floorPpm, Math.min(capPpm, rawRatePpm));
    }
}
