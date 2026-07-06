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
        long proportional = proportionalCeiling(reservedUnits, openSteps, orderQuantitySteps);
        return Math.min(availableForPosition, proportional);
    }

    public static long openingInitialMarginUnits(ContractSpec spec, long priceTicks, long openSteps) {
        if (openSteps <= 0) {
            return 0L;
        }
        if (spec.contractType().isOption()) {
            return optionSellerInitialMarginUnits(spec, priceTicks, openSteps);
        }
        return PerpetualContractMath.initialMarginUnits(spec.contractType(), openSteps, priceTicks,
                spec.notionalMultiplierUnits(), spec.priceTickUnits(), spec.settleScaleUnits(),
                spec.initialMarginRatePpm());
    }

    public static long optionPremiumUnits(ContractSpec spec, long priceTicks, long quantitySteps) {
        if (!spec.contractType().isOption()) {
            throw new IllegalArgumentException("option premium requires an option contract");
        }
        if (priceTicks <= 0 || quantitySteps <= 0) {
            throw new IllegalArgumentException("priceTicks and quantitySteps must be positive");
        }
        return Math.multiplyExact(Math.multiplyExact(priceTicks, quantitySteps), spec.notionalMultiplierUnits());
    }

    public static long optionExercisePayoffUnits(ContractSpec spec, long payoffPriceTicks, long signedQuantitySteps) {
        if (!spec.contractType().isOption()) {
            throw new IllegalArgumentException("option payoff requires an option contract");
        }
        if (payoffPriceTicks < 0 || signedQuantitySteps == 0) {
            throw new IllegalArgumentException("payoffPriceTicks must be non-negative and quantity must be non-zero");
        }
        return Math.multiplyExact(Math.multiplyExact(payoffPriceTicks, signedQuantitySteps),
                spec.notionalMultiplierUnits());
    }

    private static long optionSellerInitialMarginUnits(ContractSpec spec, long priceTicks, long openSteps) {
        long premiumUnits = optionPremiumUnits(spec, priceTicks, openSteps);
        long riskMarginUnits = PerpetualContractMath.initialMarginUnits(spec.contractType(), openSteps, priceTicks,
                spec.notionalMultiplierUnits(), spec.priceTickUnits(), spec.settleScaleUnits(),
                spec.initialMarginRatePpm());
        return Math.addExact(premiumUnits, riskMarginUnits);
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

    private static long proportionalCeiling(long totalUnits, long partSteps, long totalSteps) {
        if (totalUnits <= 0 || partSteps <= 0 || totalSteps <= 0) {
            return 0L;
        }
        return Math.addExact(Math.multiplyExact(totalUnits, partSteps), Math.subtractExact(totalSteps, 1L))
                / totalSteps;
    }
}
