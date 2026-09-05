package com.surprising.gateway.provider.service;

import java.time.Instant;

public record ProductTransferResult(
        long transferId,
        long userId,
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

    static ProductTransferResult from(long transferId, ProductTransferCommand command,
                                      ProductTransferStatus status, String errorCode, String errorMessage,
                                      Instant startedAt) {
        return new ProductTransferResult(transferId, command.userId(), command.sourceAccountType(),
                command.targetAccountType(), command.asset(), command.amountUnits(), command.referenceId(), status,
                errorCode, errorMessage, startedAt, Instant.now());
    }
}
