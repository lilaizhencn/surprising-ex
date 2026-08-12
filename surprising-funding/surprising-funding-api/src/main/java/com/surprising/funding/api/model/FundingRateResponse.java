package com.surprising.funding.api.model;

import java.time.Instant;

public record FundingRateResponse(
        String symbol,
        long sequence,
        long fundingRatePpm,
        long premiumRatePpm,
        long interestRatePpm,
        Instant fundingTime,
        int fundingIntervalHours,
        String status,
        Instant eventTime) {
}
