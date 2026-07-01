package com.surprising.trading.matching.service;

public final class MarginReleaseMath {

    private MarginReleaseMath() {
    }

    public static long releaseForRemaining(long reservedUnits,
                                           long alreadyReleasedUnits,
                                           long positionMarginUnits,
                                           long quantitySteps,
                                           long remainingSteps) {
        long unavailable = Math.addExact(alreadyReleasedUnits, positionMarginUnits);
        long releasable = Math.max(0L, Math.subtractExact(reservedUnits, unavailable));
        if (releasable == 0L || quantitySteps <= 0 || remainingSteps <= 0) {
            return 0L;
        }
        long executedSteps = Math.subtractExact(quantitySteps, remainingSteps);
        long consumedFloor = Math.multiplyExact(reservedUnits, Math.max(0L, executedSteps)) / quantitySteps;
        long proportional = Math.subtractExact(reservedUnits, consumedFloor);
        return Math.min(releasable, proportional);
    }
}
