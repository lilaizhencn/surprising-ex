package com.surprising.gateway.provider.service;

import java.time.Instant;

public record ProductTransferState(
        long transferId,
        long userId,
        String idempotencyKey,
        String requestFingerprint,
        String sourceAccountType,
        String targetAccountType,
        String asset,
        long amountUnits,
        String referenceId,
        String reason,
        ProductTransferStatus status,
        String errorCode,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt) {

    static ProductTransferState pending(long transferId, ProductTransferCreateRequest request, Instant now) {
        return new ProductTransferState(transferId, request.userId(), request.idempotencyKey(),
                request.requestFingerprint(), request.sourceAccountType(), request.targetAccountType(), request.asset(),
                request.amountUnits(), request.referenceId(), request.reason(), ProductTransferStatus.PENDING,
                null, null, now, now, null);
    }

    ProductTransferState status(ProductTransferStatus nextStatus, String nextErrorCode, String nextErrorMessage) {
        Instant now = Instant.now();
        return new ProductTransferState(transferId, userId, idempotencyKey, requestFingerprint, sourceAccountType,
                targetAccountType, asset, amountUnits, referenceId, reason, nextStatus, nextErrorCode,
                nextErrorMessage, createdAt, now, nextStatus.terminal() ? now : completedAt);
    }
}
