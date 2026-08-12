package com.surprising.gateway.provider.service;

public record ProductTransferCommand(
        long userId,
        String idempotencyKey,
        String sourceAccountType,
        String targetAccountType,
        String asset,
        long amountUnits,
        String referenceId,
        String reason) {
}
