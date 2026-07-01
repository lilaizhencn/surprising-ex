package com.surprising.account.provider.service;

import com.surprising.account.provider.model.ContractSpec;
import com.surprising.instrument.api.model.ContractType;
import java.math.BigInteger;

/**
 * Calculates signed trade-fee ledger deltas in settlement-asset units.
 * Positive fee rates charge the user, negative rates credit a maker/taker rebate.
 */
public final class TradeFeeMath {

    private static final BigInteger PPM = BigInteger.valueOf(1_000_000L);

    private TradeFeeMath() {
    }

    public static long feeDeltaUnits(ContractSpec spec,
                                     long priceTicks,
                                     long quantitySteps,
                                     boolean taker) {
        long feeRatePpm = taker ? spec.takerFeeRatePpm() : spec.makerFeeRatePpm();
        if (feeRatePpm == 0L) {
            return 0L;
        }
        if (priceTicks <= 0 || quantitySteps <= 0) {
            throw new IllegalArgumentException("priceTicks and quantitySteps must be positive");
        }
        BigInteger numerator;
        BigInteger denominator;
        if (spec.contractType() == ContractType.LINEAR_PERPETUAL) {
            numerator = big(quantitySteps)
                    .multiply(big(priceTicks))
                    .multiply(big(spec.notionalMultiplierUnits()))
                    .multiply(big(absFeeRate(feeRatePpm)));
            denominator = PPM;
        } else {
            numerator = big(quantitySteps)
                    .multiply(big(spec.notionalMultiplierUnits()))
                    .multiply(big(spec.settleScaleUnits()))
                    .multiply(big(absFeeRate(feeRatePpm)));
            denominator = big(priceTicks)
                    .multiply(big(spec.priceTickUnits()))
                    .multiply(PPM);
        }
        long feeUnits = divideCeiling(numerator, denominator);
        return feeRatePpm > 0 ? Math.negateExact(feeUnits) : feeUnits;
    }

    private static long absFeeRate(long feeRatePpm) {
        if (feeRatePpm == Long.MIN_VALUE) {
            throw new IllegalArgumentException("feeRatePpm is out of range");
        }
        return Math.absExact(feeRatePpm);
    }

    private static long divideCeiling(BigInteger numerator, BigInteger denominator) {
        if (denominator.signum() <= 0) {
            throw new IllegalArgumentException("denominator must be positive");
        }
        BigInteger[] quotientAndRemainder = numerator.divideAndRemainder(denominator);
        BigInteger rounded = quotientAndRemainder[1].signum() == 0
                ? quotientAndRemainder[0]
                : quotientAndRemainder[0].add(BigInteger.ONE);
        return rounded.longValueExact();
    }

    private static BigInteger big(long value) {
        return BigInteger.valueOf(value);
    }
}
