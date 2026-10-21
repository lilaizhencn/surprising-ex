package com.surprising.gateway.provider.model;

import java.time.Instant;

public record ProductTransferResult(
        long transferId,
        long userId,
        long recipientUserId,
        String sourceAccountType,
        String targetAccountType,
        String asset,
        long amountUnits,
        String referenceId,
        ProductTransferStatus status,
        String errorCode,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt) {

    public static ProductTransferResult from(long transferId, ProductTransferCommand command,
                                      ProductTransferStatus status, String errorCode, String errorMessage,
                                      Instant startedAt) {
        return new ProductTransferResult(transferId, command.userId(), command.recipientUserId(), command.sourceAccountType().name(),
                command.targetAccountType().name(), command.asset(), command.amountUnits(), command.referenceId(), status,
                errorCode, errorMessage, startedAt, Instant.now());
    }
}
