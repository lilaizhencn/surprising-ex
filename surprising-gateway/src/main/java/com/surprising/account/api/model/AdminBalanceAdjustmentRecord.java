package com.surprising.account.api.model;

import java.time.Instant;

public record AdminBalanceAdjustmentRecord(
        long adjustmentId,
        String adjustmentKind,
        long adminUserId,
        String adminUsername,
        long userId,
        AccountType accountType,
        String asset,
        long amountUnits,
        long balanceAfterUnits,
        String referenceId,
        String reason,
        Instant createdAt) {
}
