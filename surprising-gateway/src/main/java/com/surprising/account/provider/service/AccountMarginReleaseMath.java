package com.surprising.account.provider.service;

public final class AccountMarginReleaseMath {

    private AccountMarginReleaseMath() {
    }

    public static long releaseForExecuted(long reservedUnits,
                                          long alreadyReleasedUnits,
                                          long consumedUnits,
                                          long quantitySteps,
                                          long remainingSteps) {
        long unavailable = Math.addExact(alreadyReleasedUnits, consumedUnits);
        long releasable = Math.max(0L, Math.subtractExact(reservedUnits, unavailable));
        if (releasable == 0L || quantitySteps <= 0 || remainingSteps < 0 || remainingSteps > quantitySteps) {
            return 0L;
        }
        long executedSteps = Math.subtractExact(quantitySteps, remainingSteps);
        long executedReservationUnits = Math.multiplyExact(reservedUnits, executedSteps) / quantitySteps;
        long releaseUnits = Math.max(0L, Math.subtractExact(executedReservationUnits, unavailable));
        return Math.min(releasable, releaseUnits);
    }
}
