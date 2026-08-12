package com.surprising.funding.provider.model;

import java.time.Instant;

public record FundingSettlementWork(
        long settlementId,
        String symbol,
        Instant fundingTime,
        long fundingRatePpm,
        long instrumentVersion,
        long markPriceTicks,
        FundingPaymentCursor cursor) {
}
