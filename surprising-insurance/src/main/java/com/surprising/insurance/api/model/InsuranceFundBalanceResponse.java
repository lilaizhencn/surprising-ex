package com.surprising.insurance.api.model;

import java.time.Instant;

public record InsuranceFundBalanceResponse(
        String asset,
        long balanceUnits,
        Instant updatedAt) {
}
