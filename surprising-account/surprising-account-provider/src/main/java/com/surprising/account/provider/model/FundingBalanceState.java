package com.surprising.account.provider.model;

public record FundingBalanceState(
        long availableUnits,
        long lockedUnits,
        long deficitUnits,
        long reservedDeficitUnits) {
}
