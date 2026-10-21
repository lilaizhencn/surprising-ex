package com.surprising.aeron.service.state.account;

public record BalanceState(long availableUnits, long lockedUnits, long pendingReservedUnits) {
    public BalanceState {
        if (availableUnits < 0 || lockedUnits < 0 || pendingReservedUnits < 0
                || pendingReservedUnits > lockedUnits) {
            throw new IllegalArgumentException("invalid runtime balance state");
        }
    }
}
