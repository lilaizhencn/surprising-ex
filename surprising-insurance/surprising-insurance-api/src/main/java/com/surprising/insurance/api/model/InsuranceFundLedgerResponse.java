package com.surprising.insurance.api.model;

import java.time.Instant;

public record InsuranceFundLedgerResponse(
        long entryId,
        String asset,
        long amountUnits,
        long balanceAfterUnits,
        String referenceType,
        String referenceId,
        String reason,
        Instant createdAt) {
}
