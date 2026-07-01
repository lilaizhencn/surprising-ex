package com.surprising.funding.provider.model;

public record FundingBalanceState(
        long availableUnits,
        long lockedUnits,
        long deficitUnits) {
}
