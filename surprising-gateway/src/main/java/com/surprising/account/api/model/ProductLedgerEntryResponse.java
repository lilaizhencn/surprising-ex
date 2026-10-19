package com.surprising.account.api.model;

import java.time.Instant;
import com.fasterxml.jackson.annotation.JsonFormat;

public record ProductLedgerEntryResponse(
        @JsonFormat(shape = JsonFormat.Shape.STRING) long entryId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) long userId,
        AccountType accountType,
        String asset,
        @JsonFormat(shape = JsonFormat.Shape.STRING) long amountUnits,
        @JsonFormat(shape = JsonFormat.Shape.STRING) long balanceAfterUnits,
        String referenceType,
        String referenceId,
        String reason,
        String instrumentId,
        Instant createdAt) {
}
