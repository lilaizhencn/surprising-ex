package com.surprising.account.api.model;

import java.time.Instant;

public record OrderReleaseAccountCommand(
        long orderId,
        boolean releaseAll,
        String reason,
        Instant effectiveAt) {

    public OrderReleaseAccountCommand {
        if (orderId <= 0) {
            throw new IllegalArgumentException("orderId must be positive");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason is required");
        }
        effectiveAt = effectiveAt == null ? Instant.now() : effectiveAt;
    }
}
