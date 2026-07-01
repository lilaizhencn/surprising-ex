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
        validatePositionInputs(signedQuantitySteps, markPriceTicks, markPriceTicks, notionalMultiplierUnits,
                priceTickUnits, settleScaleUnits);
        BigInteger quantity = big(signedQuantitySteps).abs();
        if (contractType == ContractType.INVERSE_PERPETUAL) {
            BigInteger numerator = quantity.multiply(big(notionalMultiplierUnits)).multiply(big(settleScaleUnits));
            BigInteger denominator = big(markPriceTicks).multiply(big(priceTickUnits));
            return toLongRounded(numerator, denominator);
        }
        return quantity.multiply(big(markPriceTicks)).multiply(big(notionalMultiplierUnits)).longValueExact();
    }

    public static long notionalPerStepUnits(ContractType contractType,
                                            long markPriceTicks,
                                            long notionalMultiplierUnits,
                                            long priceTickUnits,
                                            long settleScaleUnits) {
        requirePositive(markPriceTicks, "markPriceTicks");
        requirePositive(notionalMultiplierUnits, "notionalMultiplierUnits");
        requirePositive(priceTickUnits, "priceTickUnits");
        requirePositive(settleScaleUnits, "settleScaleUnits");
        if (contractType == ContractType.INVERSE_PERPETUAL) {
            BigInteger numerator = big(notionalMultiplierUnits).multiply(big(settleScaleUnits));
            BigInteger denominator = big(markPriceTicks).multiply(big(priceTickUnits));
            return toLongRounded(numerator, denominator);
        }
        return big(markPriceTicks).multiply(big(notionalMultiplierUnits)).longValueExact();
    }

    public static long unrealizedPnlUnits(ContractType contractType,
                                          long signedQuantitySteps,
                                          long entryPriceTicks,
                                          long markPriceTicks,
                                          long notionalMultiplierUnits,
                                          long priceTickUnits,
                                          long settleScaleUnits) {
        validatePositionInputs(signedQuantitySteps, entryPriceTicks, markPriceTicks, notionalMultiplierUnits,
                priceTickUnits, settleScaleUnits);
        BigInteger priceDiff = big(Math.subtractExact(markPriceTicks, entryPriceTicks));
        if (contractType == ContractType.INVERSE_PERPETUAL) {
            BigInteger numerator = big(signedQuantitySteps)
                    .multiply(big(notionalMultiplierUnits))
                    .multiply(big(settleScaleUnits))
                    .multiply(priceDiff);
            BigInteger denominator = big(entryPriceTicks)
                    .multiply(big(markPriceTicks))
                    .multiply(big(priceTickUnits));
            return toLongRounded(numerator, denominator);
        }
        return big(signedQuantitySteps)
                .multiply(priceDiff)
                .multiply(big(notionalMultiplierUnits))
                .longValueExact();
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
        validatePositionInputs(signedQuantitySteps, markPriceTicks, markPriceTicks, notionalMultiplierUnits,
                priceTickUnits, settleScaleUnits);
        requirePositive(marginRatePpm, "marginRatePpm");
        BigInteger quantity = big(signedQuantitySteps).abs();
        BigInteger numerator;
        BigInteger denominator;
        if (contractType == ContractType.INVERSE_PERPETUAL) {
            numerator = quantity
                    .multiply(big(notionalMultiplierUnits))
                    .multiply(big(settleScaleUnits))
                    .multiply(big(marginRatePpm));
            denominator = big(markPriceTicks).multiply(big(priceTickUnits)).multiply(PPM);
        } else {
            numerator = quantity
                    .multiply(big(markPriceTicks))
                    .multiply(big(notionalMultiplierUnits))
                    .multiply(big(marginRatePpm));
            denominator = PPM;
        }
        return divideCeiling(numerator, denominator);
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
