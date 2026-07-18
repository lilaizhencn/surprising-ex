package com.surprising.funding.provider.model;

public record FundingPaymentDispatch(long paymentId, String commandId) {

    public FundingPaymentDispatch {
        if (paymentId <= 0) {
            throw new IllegalArgumentException("paymentId must be positive");
        }
        if (commandId == null || commandId.isBlank()) {
            throw new IllegalArgumentException("commandId is required");
        }
    }
}
