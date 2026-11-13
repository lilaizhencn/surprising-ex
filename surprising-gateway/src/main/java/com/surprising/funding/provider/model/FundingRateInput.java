package com.surprising.funding.provider.model;

import java.time.Instant;

public record FundingRateInput(
        String instrumentId,
        long sequence,
        long premiumRatePpm,
        long interestRatePpm,
        long fundingRateFloorPpm,
        long fundingRateCapPpm,
        int fundingIntervalHours,
        Instant eventTime) {
}
