package com.surprising.account.api.model;

/**
 * Durable payload for basic-wallet balance adjustments.
 *
 * <p>Admin identity is part of the immutable command identity so an idempotency key cannot be
 * reused by a different operator.</p>
 */
public record BalanceAdjustmentAccountCommand(
        BalanceAdjustmentRequest request,
        String adminUserId,
        String adminUsername) {

    public BalanceAdjustmentAccountCommand {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        adminUserId = normalize(adminUserId);
        adminUsername = normalize(adminUsername);
    }

    public boolean adminOperation() {
        return adminUserId != null;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
