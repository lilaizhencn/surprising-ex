package com.surprising.funding.provider.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

public final class FundingTime {

    private FundingTime() {
    }

    public static Instant nextFundingTime(Instant now, int intervalHours) {
        if (intervalHours <= 0) {
            throw new IllegalArgumentException("intervalHours must be positive");
        }
        long intervalSeconds = Math.multiplyExact(intervalHours, 3600L);
        long epochSeconds = now.truncatedTo(ChronoUnit.SECONDS).getEpochSecond();
        long next = ((epochSeconds / intervalSeconds) + 1L) * intervalSeconds;
        return Instant.ofEpochSecond(next);
    }

    public static String rateDecimalString(long fundingRatePpm) {
        boolean negative = fundingRatePpm < 0;
        long abs = Math.absExact(fundingRatePpm);
        long whole = abs / FundingMath.RATE_SCALE;
        long fraction = abs % FundingMath.RATE_SCALE;
        return (negative ? "-" : "") + whole + "." + String.format("%06d", fraction);
    }
}
