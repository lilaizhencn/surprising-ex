package com.surprising.funding.api.model;

import java.time.Instant;

/** Final rate comes from committed Core history. Prediction components not retained in
 * old Core commands remain unknown, rather than being reconstructed from a current quote. */
public record FundingRateHistoryResponse(String instrumentId, long sequence, long fundingRatePpm,
                                         Long premiumRatePpm, Long interestRatePpm, Instant fundingTime,
                                         Integer fundingIntervalHours, String status, Instant eventTime) {}
