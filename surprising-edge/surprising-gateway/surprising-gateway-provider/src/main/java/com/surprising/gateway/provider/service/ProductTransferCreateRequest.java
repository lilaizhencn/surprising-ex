package com.surprising.gateway.provider.service;

public record ProductTransferCreateRequest(
        long userId,
        String idempotencyKey,
        String requestFingerprint,
        String sourceAccountType,
        String targetAccountType,
        String asset,
        long amountUnits,
        String referenceId,
        String reason) {
}
