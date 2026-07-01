package com.surprising.account.provider.model;

public record BalanceSettlementState(
        long availableUnits,
        long lockedUnits,
        long deficitUnits) {
}
