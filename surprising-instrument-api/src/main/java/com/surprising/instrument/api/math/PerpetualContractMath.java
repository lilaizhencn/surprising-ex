package com.surprising.instrument.api.math;

import com.surprising.instrument.api.model.ContractType;
import java.math.BigInteger;

/**
 * Shared long-unit formulas for perpetual contracts.
 * Public inputs and outputs stay in exchange-core compatible ticks, steps, ppm, and asset units.
 */
public final class PerpetualContractMath {

    private static final BigInteger PPM = BigInteger.valueOf(1_000_000L);

    private PerpetualContractMath() {
    }

    public static long notionalUnits(ContractType contractType,
                                     long signedQuantitySteps,
                                     long markPriceTicks,
                                     long notionalMultiplierUnits,
                                     long priceTickUnits,
                                     long settleScaleUnits) {
        requireLinearOrInverse(contractType);
        validatePositionInputs(signedQuantitySteps, markPriceTicks, markPriceTicks, notionalMultiplierUnits,
                priceTickUnits, settleScaleUnits);
        if (contractType.isInverse()) {
            long numeratorLong = positiveProduct(positiveProduct(Math.abs(signedQuantitySteps), notionalMultiplierUnits), settleScaleUnits);
            long denominatorLong = positiveProduct(markPriceTicks, priceTickUnits);
            if (numeratorLong >= 0 && denominatorLong > 0) return roundedPositive(numeratorLong, denominatorLong);
            BigInteger quantity = big(signedQuantitySteps).abs();
            BigInteger numerator = quantity.multiply(big(notionalMultiplierUnits)).multiply(big(settleScaleUnits));
            BigInteger denominator = big(markPriceTicks).multiply(big(priceTickUnits));
            return toLongRounded(numerator, denominator);
        }
        try {
            return Math.multiplyExact(Math.multiplyExact(Math.absExact(signedQuantitySteps), markPriceTicks),
                    notionalMultiplierUnits);
        } catch (ArithmeticException overflow) {
            return big(signedQuantitySteps).abs().multiply(big(markPriceTicks))
                    .multiply(big(notionalMultiplierUnits)).longValueExact();
        }
    }

    public static long notionalPerStepUnits(ContractType contractType,
                                            long markPriceTicks,
                                            long notionalMultiplierUnits,
                                            long priceTickUnits,
                                            long settleScaleUnits) {
        requireLinearOrInverse(contractType);
        requirePositive(markPriceTicks, "markPriceTicks");
        requirePositive(notionalMultiplierUnits, "notionalMultiplierUnits");
        requirePositive(priceTickUnits, "priceTickUnits");
        requirePositive(settleScaleUnits, "settleScaleUnits");
        if (contractType.isInverse()) {
            long numeratorLong = positiveProduct(notionalMultiplierUnits, settleScaleUnits);
            long denominatorLong = positiveProduct(markPriceTicks, priceTickUnits);
            if (numeratorLong >= 0 && denominatorLong > 0) return roundedPositive(numeratorLong, denominatorLong);
            BigInteger numerator = big(notionalMultiplierUnits).multiply(big(settleScaleUnits));
            BigInteger denominator = big(markPriceTicks).multiply(big(priceTickUnits));
            return toLongRounded(numerator, denominator);
        }
        try {
            return Math.multiplyExact(markPriceTicks, notionalMultiplierUnits);
        } catch (ArithmeticException overflow) {
            return big(markPriceTicks).multiply(big(notionalMultiplierUnits)).longValueExact();
        }
    }

    public static long unrealizedPnlUnits(ContractType contractType,
                                          long signedQuantitySteps,
                                          long entryPriceTicks,
                                          long markPriceTicks,
                                          long notionalMultiplierUnits,
                                          long priceTickUnits,
                                          long settleScaleUnits) {
        requireLinearOrInverse(contractType);
        validatePositionInputs(signedQuantitySteps, entryPriceTicks, markPriceTicks, notionalMultiplierUnits,
                priceTickUnits, settleScaleUnits);
        long priceDiff = Math.subtractExact(markPriceTicks, entryPriceTicks);
        if (priceDiff == 0 || signedQuantitySteps == 0) return 0;
        if (contractType.isInverse()) {
            long numeratorLong = positiveProduct(positiveProduct(positiveProduct(Math.abs(signedQuantitySteps),
                    notionalMultiplierUnits), settleScaleUnits), Math.abs(priceDiff));
            long denominatorLong = positiveProduct(positiveProduct(entryPriceTicks, markPriceTicks), priceTickUnits);
            if (numeratorLong >= 0 && denominatorLong > 0) {
                long rounded = roundedPositive(numeratorLong, denominatorLong);
                return (signedQuantitySteps < 0) == (priceDiff < 0) ? rounded : -rounded;
            }
            BigInteger inversePriceDiff = big(priceDiff);
            BigInteger numerator = big(signedQuantitySteps)
                    .multiply(big(notionalMultiplierUnits))
                    .multiply(big(settleScaleUnits))
                    .multiply(inversePriceDiff);
            BigInteger denominator = big(entryPriceTicks)
                    .multiply(big(markPriceTicks))
                    .multiply(big(priceTickUnits));
            return toLongRounded(numerator, denominator);
        }
        try {
            return Math.multiplyExact(Math.multiplyExact(signedQuantitySteps, priceDiff),
                    notionalMultiplierUnits);
        } catch (ArithmeticException overflow) {
            return big(signedQuantitySteps)
                    .multiply(big(priceDiff))
                    .multiply(big(notionalMultiplierUnits))
                    .longValueExact();
        }
    }

    public static long maintenanceMarginUnits(ContractType contractType,
                                              long signedQuantitySteps,
                                              long markPriceTicks,
                                              long notionalMultiplierUnits,
                                              long priceTickUnits,
                                              long settleScaleUnits,
                                              long maintenanceMarginRatePpm) {
        return marginUnits(contractType, signedQuantitySteps, markPriceTicks, notionalMultiplierUnits,
                priceTickUnits, settleScaleUnits, maintenanceMarginRatePpm);
    }

    public static long initialMarginUnits(ContractType contractType,
                                          long quantitySteps,
                                          long fillPriceTicks,
                                          long notionalMultiplierUnits,
                                          long priceTickUnits,
                                          long settleScaleUnits,
                                          long initialMarginRatePpm) {
        return marginUnits(contractType, quantitySteps, fillPriceTicks, notionalMultiplierUnits,
                priceTickUnits, settleScaleUnits, initialMarginRatePpm);
    }

    private static long marginUnits(ContractType contractType,
                                    long signedQuantitySteps,
                                    long markPriceTicks,
                                    long notionalMultiplierUnits,
                                    long priceTickUnits,
                                    long settleScaleUnits,
                                    long marginRatePpm) {
        requireLinearOrInverse(contractType);
        validatePositionInputs(signedQuantitySteps, markPriceTicks, markPriceTicks, notionalMultiplierUnits,
                priceTickUnits, settleScaleUnits);
        requirePositive(marginRatePpm, "marginRatePpm");
        if (contractType.isInverse()) {
            long numeratorLong = positiveProduct(positiveProduct(positiveProduct(Math.abs(signedQuantitySteps),
                    notionalMultiplierUnits), settleScaleUnits), marginRatePpm);
            long denominatorLong = positiveProduct(positiveProduct(markPriceTicks, priceTickUnits), 1_000_000L);
            if (numeratorLong >= 0 && denominatorLong > 0) return divideCeiling(numeratorLong, denominatorLong);
            BigInteger quantity = big(signedQuantitySteps).abs();
            BigInteger numerator = quantity
                    .multiply(big(notionalMultiplierUnits))
                    .multiply(big(settleScaleUnits))
                    .multiply(big(marginRatePpm));
            BigInteger denominator = big(markPriceTicks).multiply(big(priceTickUnits)).multiply(PPM);
            return divideCeiling(numerator, denominator);
        }
        try {
            long linearNumerator = Math.multiplyExact(Math.multiplyExact(Math.absExact(signedQuantitySteps),
                            markPriceTicks),
                    notionalMultiplierUnits);
            linearNumerator = Math.multiplyExact(linearNumerator, marginRatePpm);
            return divideCeiling(linearNumerator, 1_000_000L);
        } catch (ArithmeticException overflow) {
            BigInteger numerator = big(signedQuantitySteps).abs()
                    .multiply(big(markPriceTicks))
                    .multiply(big(notionalMultiplierUnits))
                    .multiply(big(marginRatePpm));
            return divideCeiling(numerator, PPM);
        }
    }

    private static void requireLinearOrInverse(ContractType contractType) {
        if (contractType == null || (!contractType.isLinear() && !contractType.isInverse())) {
            throw new IllegalArgumentException("unsupported contract type: " + contractType);
        }
    }

    private static void validatePositionInputs(long signedQuantitySteps,
                                               long entryPriceTicks,
                                               long markPriceTicks,
                                               long notionalMultiplierUnits,
                                               long priceTickUnits,
                                               long settleScaleUnits) {
        if (signedQuantitySteps == 0) {
            throw new IllegalArgumentException("signedQuantitySteps must be non-zero");
        }
        requirePositive(entryPriceTicks, "entryPriceTicks");
        requirePositive(markPriceTicks, "markPriceTicks");
        requirePositive(notionalMultiplierUnits, "notionalMultiplierUnits");
        requirePositive(priceTickUnits, "priceTickUnits");
        requirePositive(settleScaleUnits, "settleScaleUnits");
    }

    // Negative sentinel selects the exact wide-integer path; ordinary overflow creates no exception.
    private static long positiveProduct(long left, long right) {
        if (left < 0 || right < 0 || right != 0 && left > Long.MAX_VALUE / right) return -1;
        return left * right;
    }

    private static long roundedPositive(long numerator, long denominator) {
        long quotient = numerator / denominator;
        long remainder = numerator % denominator;
        return remainder >= denominator - remainder ? Math.incrementExact(quotient) : quotient;
    }

    private static long divideCeiling(BigInteger numerator, BigInteger denominator) {
        if (denominator.signum() <= 0 || numerator.signum() < 0) {
            throw new IllegalArgumentException("positive numerator and denominator are required");
        }
        BigInteger[] quotientAndRemainder = numerator.divideAndRemainder(denominator);
        BigInteger rounded = quotientAndRemainder[1].signum() == 0
                ? quotientAndRemainder[0]
                : quotientAndRemainder[0].add(BigInteger.ONE);
        return rounded.longValueExact();
    }

    private static long divideCeiling(long numerator, long denominator) {
        long quotient = numerator / denominator;
        return numerator % denominator == 0 ? quotient : Math.addExact(quotient, 1);
    }

    private static long toLongRounded(BigInteger numerator, BigInteger denominator) {
        if (denominator.signum() <= 0) {
            throw new IllegalArgumentException("denominator must be positive");
        }
        BigInteger sign = numerator.signum() < 0 ? BigInteger.valueOf(-1L) : BigInteger.ONE;
        BigInteger absolute = numerator.abs();
        BigInteger[] quotientAndRemainder = absolute.divideAndRemainder(denominator);
        BigInteger rounded = quotientAndRemainder[1].shiftLeft(1).compareTo(denominator) >= 0
                ? quotientAndRemainder[0].add(BigInteger.ONE)
                : quotientAndRemainder[0];
        return rounded.multiply(sign).longValueExact();
    }

    private static BigInteger big(long value) {
        return BigInteger.valueOf(value);
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
