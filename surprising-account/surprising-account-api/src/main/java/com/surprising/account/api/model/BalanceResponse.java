package com.surprising.account.api.model;

import java.time.Instant;

public record BalanceResponse(
        long userId,
        String asset,
        long availableUnits,
        long lockedUnits,
        long equityUnits,
        Instant updatedAt) {
}
