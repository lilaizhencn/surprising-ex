package com.surprising.funding.provider.model;

public record FundingPaymentCandidate(
        long userId,
        String symbol,
        String asset,
        long signedQuantitySteps,
        long notionalUnits,
        long fundingRatePpm,
        long amountUnits) {
}
