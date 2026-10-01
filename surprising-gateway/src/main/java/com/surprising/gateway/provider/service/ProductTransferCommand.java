package com.surprising.gateway.provider.service;

public record ProductTransferCommand(
        long userId,
        String idempotencyKey,
        String sourceAccountType,
        String targetAccountType,
        String asset,
        long amountUnits,
        String referenceId,
        String reason,
        long recipientUserId) {
    public ProductTransferCommand(long userId, String idempotencyKey, String sourceAccountType,
            String targetAccountType, String asset, long amountUnits, String referenceId, String reason) {
        this(userId, idempotencyKey, sourceAccountType, targetAccountType, asset, amountUnits,
                referenceId, reason, userId);
    }
}
