package com.surprising.account.api.model;

import java.time.Instant;

public record OrderReleaseAccountCommand(
        long orderId,
        boolean releaseAll,
        long quantitySteps,
        long remainingQuantitySteps,
        boolean reservationExpected,
        String reason,
        Instant effectiveAt) {

    public OrderReleaseAccountCommand {
        if (orderId <= 0) {
            throw new IllegalArgumentException("orderId must be positive");
        }
        if (quantitySteps <= 0 || remainingQuantitySteps < 0 || remainingQuantitySteps > quantitySteps) {
            throw new IllegalArgumentException("invalid order quantity snapshot");
        }
        if (!releaseAll && remainingQuantitySteps == quantitySteps) {
            throw new IllegalArgumentException("partial release requires executed quantity");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason is required");
        }
        effectiveAt = effectiveAt == null ? Instant.now() : effectiveAt;
    }
}
