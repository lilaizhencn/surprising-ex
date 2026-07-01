package com.surprising.account.provider.service;

import com.surprising.account.provider.model.ContractSpec;
import com.surprising.instrument.api.math.PerpetualContractMath;
import com.surprising.trading.api.model.OrderSide;

/**
 * Long-only margin math used after a match trade is received.
 * The account service decides how much of a fill closes old exposure and how much opens new exposure.
 */
public final class MarginTransferMath {

    private MarginTransferMath() {
    }

    public static long closeSteps(long currentSignedQuantitySteps, OrderSide fillSide, long fillQuantitySteps) {
        if (fillQuantitySteps <= 0 || currentSignedQuantitySteps == 0) {
            return 0L;
        }
        long signedFill = fillSide == OrderSide.BUY ? fillQuantitySteps : -fillQuantitySteps;
        boolean sameDirection = (currentSignedQuantitySteps > 0 && signedFill > 0)
                || (currentSignedQuantitySteps < 0 && signedFill < 0);
        if (sameDirection) {
            return 0L;
        }
        return Math.min(Math.absExact(currentSignedQuantitySteps), fillQuantitySteps);
    }

    public static long openSteps(long currentSignedQuantitySteps, OrderSide fillSide, long fillQuantitySteps) {
        return Math.subtractExact(fillQuantitySteps, closeSteps(currentSignedQuantitySteps, fillSide, fillQuantitySteps));
    }

    public static long orderMarginReleaseAmount(long reservedUnits,
                                                long releasedUnits,
                                                long positionMarginUnits,
                                                long orderQuantitySteps,
                                                long closeSteps,
                                                boolean sweepRemainder) {
        long availableForRelease = availableOrderMargin(reservedUnits, releasedUnits, positionMarginUnits);
        if (availableForRelease == 0L || closeSteps <= 0) {
            return 0L;
        }
        if (sweepRemainder) {
            return availableForRelease;
        }
        long proportional = proportional(reservedUnits, closeSteps, orderQuantitySteps);
        return Math.min(availableForRelease, proportional);
    }

    public static long orderMarginConsumeAmount(long reservedUnits,
                                                long releasedUnits,
                                                long positionMarginUnits,
                                                long orderQuantitySteps,
                                                long openSteps,
                                                boolean sweepRemainder) {
        long availableForPosition = availableOrderMargin(reservedUnits, releasedUnits, positionMarginUnits);
        if (availableForPosition == 0L || openSteps <= 0) {
            return 0L;
        }
        if (sweepRemainder) {
            return availableForPosition;
        }
        long proportional = proportional(reservedUnits, openSteps, orderQuantitySteps);
        return Math.min(availableForPosition, proportional);
    }

    public static long openingInitialMarginUnits(ContractSpec spec, long priceTicks, long openSteps) {
        if (openSteps <= 0) {
            return 0L;
        }
        return PerpetualContractMath.initialMarginUnits(spec.contractType(), openSteps, priceTicks,
                spec.notionalMultiplierUnits(), spec.priceTickUnits(), spec.settleScaleUnits(),
                spec.initialMarginRatePpm());
    }

    public static long excessOrderMarginUnits(long allocatedUnits, long actualMarginUnits) {
        if (allocatedUnits < 0 || actualMarginUnits < 0) {
            throw new IllegalArgumentException("margin units must be non-negative");
        }
        if (actualMarginUnits > allocatedUnits) {
            throw new IllegalStateException("reserved order margin is smaller than actual fill margin");
        }
        return allocatedUnits - actualMarginUnits;
    }

    public static long positionMarginReleaseAmount(long marginUnits, long closeSteps, long positionAbsSteps) {
        if (marginUnits <= 0 || closeSteps <= 0 || positionAbsSteps <= 0) {
            return 0L;
        }
        if (closeSteps >= positionAbsSteps) {
            return marginUnits;
        }
        return proportional(marginUnits, closeSteps, positionAbsSteps);
    }

    private static long availableOrderMargin(long reservedUnits, long releasedUnits, long positionMarginUnits) {
        return Math.max(0L, Math.subtractExact(reservedUnits, Math.addExact(releasedUnits, positionMarginUnits)));
    }

    private static long proportional(long totalUnits, long partSteps, long totalSteps) {
        if (totalUnits <= 0 || partSteps <= 0 || totalSteps <= 0) {
            return 0L;
        }
        return Math.multiplyExact(totalUnits, partSteps) / totalSteps;
    }
}
