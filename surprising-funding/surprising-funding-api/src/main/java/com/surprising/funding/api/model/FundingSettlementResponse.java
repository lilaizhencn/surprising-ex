package com.surprising.funding.api.model;

import java.time.Instant;

public record FundingSettlementResponse(
        long settlementId,
        String symbol,
        Instant fundingTime,
        long fundingRatePpm,
        long totalLongPaymentUnits,
        long totalShortPaymentUnits,
        int positionCount,
        String status,
        Instant createdAt,
        Instant updatedAt) {
}
