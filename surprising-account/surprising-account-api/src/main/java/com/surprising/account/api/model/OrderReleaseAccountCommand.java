package com.surprising.account.api.model;

import java.time.Instant;

public record OrderReleaseAccountCommand(
        long orderId,
        boolean releaseAll,
        long quantitySteps,
        long remainingQuantitySteps,
        boolean reservationExpected,
        AccountType reservationAccountType,
        String reservationAsset,
        long reservedUnits,
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
        if (reservedUnits < 0L
                || (reservedUnits == 0L && (reservationAccountType != null || reservationAsset != null))
                || (reservedUnits > 0L && (reservationAccountType == null
                || reservationAsset == null || reservationAsset.isBlank()))) {
            throw new IllegalArgumentException("invalid order release reservation snapshot");
        }
        if (reservationExpected && reservedUnits <= 0L) {
            throw new IllegalArgumentException("expected reservation snapshot is required");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason is required");
        }
        effectiveAt = effectiveAt == null ? Instant.now() : effectiveAt;
    }
}
