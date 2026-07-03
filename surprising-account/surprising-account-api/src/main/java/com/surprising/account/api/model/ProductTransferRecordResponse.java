package com.surprising.account.api.model;

import java.time.Instant;

public record ProductTransferRecordResponse(
        long transferId,
        long userId,
        AccountType sourceAccountType,
        AccountType targetAccountType,
        String asset,
        long amountUnits,
        String referenceId,
        String status,
        String reason,
        Instant createdAt,
        Instant updatedAt) {
}
