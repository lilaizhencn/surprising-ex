package com.surprising.funding.provider.model;

import java.time.Instant;

public record FundingPaymentResult(
        String commandId,
        long userId,
        String status,
        String errorCode,
        String errorMessage,
        Instant completedAt) {
}
