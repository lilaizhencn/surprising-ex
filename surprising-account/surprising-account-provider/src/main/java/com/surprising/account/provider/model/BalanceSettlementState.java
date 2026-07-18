package com.surprising.account.provider.model;

public record BalanceSettlementState(
        long availableUnits,
        long lockedUnits,
        long deficitUnits,
        long reservedDeficitUnits) {

    public BalanceSettlementState(long availableUnits, long lockedUnits, long deficitUnits) {
        this(availableUnits, lockedUnits, deficitUnits, 0L);
    }
}
