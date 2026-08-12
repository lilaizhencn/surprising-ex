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

    static ProductTransferResult from(ProductTransferState state) {
        return new ProductTransferResult(state.transferId(), state.userId(), state.sourceAccountType(),
                state.targetAccountType(), state.asset(), state.amountUnits(), state.referenceId(), state.status(),
                state.errorCode(), state.errorMessage(), state.createdAt(), state.updatedAt());
    }
}
