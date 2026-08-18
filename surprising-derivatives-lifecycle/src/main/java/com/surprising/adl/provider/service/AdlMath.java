package com.surprising.adl.provider.service;

public final class AdlMath {

    public static final long PPM = 1_000_000L;

    private AdlMath() {
    }

    public static long profitRatePpm(long profitUnits, long notionalUnits) {
        if (profitUnits <= 0 || notionalUnits <= 0) {
            return 0L;
        }
        return multiplyDivideCapped(profitUnits, PPM, notionalUnits);
    }

    public static long effectiveLeveragePpm(long notionalUnits, long marginUnits) {
        if (notionalUnits <= 0) {
            return 0L;
        }
        if (marginUnits <= 0) {
            return Long.MAX_VALUE;
        }
        return multiplyDivideCapped(notionalUnits, PPM, marginUnits);
    }

    public static long priorityScorePpm(long profitRatePpm, long effectiveLeveragePpm) {
        if (profitRatePpm <= 0 || effectiveLeveragePpm <= 0) {
            return 0L;
        }
        return multiplyDivideCapped(profitRatePpm, effectiveLeveragePpm, PPM);
    }

    public static long closeStepsForCover(long remainingDeficitUnits,
                                          long absQuantitySteps,
                                          long unrealizedProfitUnits) {
        if (remainingDeficitUnits <= 0 || absQuantitySteps <= 0 || unrealizedProfitUnits <= 0) {
            return 0L;
        }
        long steps = ceilMultiplyDivideCapped(remainingDeficitUnits, absQuantitySteps, unrealizedProfitUnits);
        return Math.max(1L, Math.min(absQuantitySteps, steps));
    }

    public static long proportionalUnits(long totalUnits, long partSteps, long totalSteps) {
        if (totalUnits <= 0 || partSteps <= 0 || totalSteps <= 0) {
            return 0L;
        }
        return multiplyDivideCapped(totalUnits, partSteps, totalSteps);
    }

    private static long ceilMultiplyDivideCapped(long left, long right, long divisor) {
        long floor = multiplyDivideCapped(left, right, divisor);
        try {
            long product = Math.multiplyExact(left, right);
            return product % divisor == 0 ? floor : Math.addExact(floor, 1L);
        } catch (ArithmeticException ex) {
            return Long.MAX_VALUE;
        }
    }

    private static long multiplyDivideCapped(long left, long right, long divisor) {
        if (divisor <= 0) {
            throw new IllegalArgumentException("divisor must be positive");
        }
        try {
            return Math.multiplyExact(left, right) / divisor;
        } catch (ArithmeticException ex) {
            return Long.MAX_VALUE;
        }
    }
}
