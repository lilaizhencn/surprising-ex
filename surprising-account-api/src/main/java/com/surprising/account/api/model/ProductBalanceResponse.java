package com.surprising.account.api.model;

import java.time.Instant;

public record ProductBalanceResponse(
        long userId,
        AccountType accountType,
        String asset,
        long availableUnits,
        long lockedUnits,
        long equityUnits,
        Instant updatedAt) {
}
