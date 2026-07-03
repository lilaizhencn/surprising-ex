package com.surprising.account.api.model;

import java.time.Instant;

public record ProductLedgerEntryResponse(
        long entryId,
        long userId,
        AccountType accountType,
        String asset,
        long amountUnits,
        long balanceAfterUnits,
        String referenceType,
        String referenceId,
        String reason,
        Instant createdAt) {
}
