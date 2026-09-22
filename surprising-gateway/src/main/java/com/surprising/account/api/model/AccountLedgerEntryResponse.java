package com.surprising.account.api.model;

import java.time.Instant;

public record AccountLedgerEntryResponse(
        long entryId,
        long userId,
        String asset,
        long amountUnits,
        long balanceAfterUnits,
        String referenceType,
        String referenceId,
        String reason,
        Long tradeId,
        Long orderId,
        String symbol,
        Long feeRatePpm,
        Instant createdAt) {
}
