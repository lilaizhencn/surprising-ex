package com.surprising.funding.provider.model;

public record FundingPaymentWrite(
        long paymentId,
        String commandId,
        FundingPaymentCandidate payment) {
}
