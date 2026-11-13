package com.surprising.account.api.model;

import java.time.Instant;

public record ProductTransferResponse(
        long transferId,
        long userId,
        AccountType sourceAccountType,
        AccountType targetAccountType,
        String asset,
        long amountUnits,
        String referenceId,
        String status,
        ProductBalanceResponse sourceBalance,
        ProductBalanceResponse targetBalance,
        Instant createdAt) {
}
