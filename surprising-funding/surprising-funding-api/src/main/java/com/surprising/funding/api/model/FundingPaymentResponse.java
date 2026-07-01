package com.surprising.funding.api.model;

import java.time.Instant;

public record FundingPaymentResponse(
        long paymentId,
        long settlementId,
        long userId,
        String symbol,
        String asset,
        long signedQuantitySteps,
        long notionalUnits,
        long fundingRatePpm,
        long amountUnits,
        Instant createdAt) {
}
