package com.surprising.account.api.model;

/**
 * Durable payload for product-account balance adjustments.
 */
public record ProductBalanceAdjustmentAccountCommand(
        ProductBalanceAdjustmentRequest request,
        String adminUserId,
        String adminUsername) {

    public ProductBalanceAdjustmentAccountCommand {
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
