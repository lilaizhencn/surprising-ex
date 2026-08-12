package com.surprising.funding.provider.service;

import com.surprising.funding.provider.model.FundingBalanceState;

public final class FundingMath {

    public static final long RATE_SCALE = 1_000_000L;

    private FundingMath() {
    }

    public static long clampRate(long rawRatePpm, long floorPpm, long capPpm) {
        return Math.max(floorPpm, Math.min(capPpm, rawRatePpm));
    }

    public static long paymentAmount(long signedQuantitySteps, long notionalUnits, long fundingRatePpm) {
        if (signedQuantitySteps == 0 || notionalUnits == 0 || fundingRatePpm == 0) {
            return 0L;
        }
        long gross = Math.multiplyExact(notionalUnits, Math.absExact(fundingRatePpm)) / RATE_SCALE;
        if (gross == 0L) {
            return 0L;
        }
        boolean longPays = fundingRatePpm > 0;
        boolean positionLong = signedQuantitySteps > 0;
        return longPays == positionLong ? -gross : gross;
    }

    public static FundingBalanceState applyPayment(long availableUnits,
                                                   long lockedUnits,
                                                   long deficitUnits,
                                                   long amountUnits) {
        return applyPayment(availableUnits, lockedUnits, deficitUnits, amountUnits, lockedUnits);
    }

    public static FundingBalanceState applyPayment(long availableUnits,
                                                   long lockedUnits,
                                                   long deficitUnits,
                                                   long amountUnits,
                                                   long maxLockedDebitUnits) {
        if (amountUnits == 0L) {
            return new FundingBalanceState(availableUnits, lockedUnits, deficitUnits);
        }
        if (amountUnits > 0L) {
            long deficitOffset = Math.min(deficitUnits, amountUnits);
            long nextDeficit = Math.subtractExact(deficitUnits, deficitOffset);
            long remaining = Math.subtractExact(amountUnits, deficitOffset);
            return new FundingBalanceState(Math.addExact(availableUnits, remaining), lockedUnits, nextDeficit);
        }
        long charge = Math.negateExact(amountUnits);
        long fromAvailable = Math.min(availableUnits, charge);
        long nextAvailable = Math.subtractExact(availableUnits, fromAvailable);
        long remaining = Math.subtractExact(charge, fromAvailable);
        long lockedDebitLimit = Math.min(lockedUnits, Math.max(0L, maxLockedDebitUnits));
        long fromLocked = Math.min(lockedDebitLimit, remaining);
        long nextLocked = Math.subtractExact(lockedUnits, fromLocked);
        long nextDeficit = Math.addExact(deficitUnits, Math.subtractExact(remaining, fromLocked));
        return new FundingBalanceState(nextAvailable, nextLocked, nextDeficit);
    }

}
