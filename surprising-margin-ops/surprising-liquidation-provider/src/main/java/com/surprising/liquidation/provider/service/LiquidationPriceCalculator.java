package com.surprising.liquidation.provider.service;

import com.surprising.instrument.api.math.PerpetualContractMath;
import com.surprising.liquidation.provider.model.LiquidationPricingDecision;
import com.surprising.liquidation.provider.model.LiquidationPricingInput;
import java.math.BigInteger;
import org.springframework.stereotype.Component;

@Component
public class LiquidationPriceCalculator {

    private static final BigInteger PPM = BigInteger.valueOf(1_000_000L);

    public LiquidationPricingDecision decide(LiquidationPricingInput input, long liquidationFeeRatePpm) {
        long feeUnits = liquidationFeeUnits(input, liquidationFeeRatePpm);
        return new LiquidationPricingDecision(
                adversePriceAtEquity(input, 0L),
                adversePriceAtEquity(input, feeUnits),
                Math.max(0L, liquidationFeeRatePpm),
                feeUnits);
    }

    private long liquidationFeeUnits(LiquidationPricingInput input, long liquidationFeeRatePpm) {
        if (liquidationFeeRatePpm <= 0) {
            return 0L;
        }
        long notionalUnits = PerpetualContractMath.notionalUnits(input.contractType(), input.signedQuantitySteps(),
                input.markPriceTicks(), input.notionalMultiplierUnits(), input.priceTickUnits(),
                input.settleScaleUnits());
        BigInteger numerator = BigInteger.valueOf(notionalUnits).multiply(BigInteger.valueOf(liquidationFeeRatePpm));
        BigInteger[] quotientAndRemainder = numerator.divideAndRemainder(PPM);
        BigInteger rounded = quotientAndRemainder[1].signum() == 0
                ? quotientAndRemainder[0]
                : quotientAndRemainder[0].add(BigInteger.ONE);
        return rounded.longValueExact();
    }

    private long adversePriceAtEquity(LiquidationPricingInput input, long targetEquityUnits) {
        if (input.equityUnits() <= targetEquityUnits) {
            return input.markPriceTicks();
        }
        if (input.signedQuantitySteps() > 0) {
            return longAdversePrice(input, targetEquityUnits);
        }
        return shortAdversePrice(input, targetEquityUnits);
    }

    private long longAdversePrice(LiquidationPricingInput input, long targetEquityUnits) {
        long low = 1L;
        long high = input.markPriceTicks();
        if (equityAt(input, low) > targetEquityUnits) {
            return 0L;
        }
        while (low + 1L < high) {
            long mid = low + ((high - low) >>> 1);
            if (equityAt(input, mid) <= targetEquityUnits) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return low;
    }

    private long shortAdversePrice(LiquidationPricingInput input, long targetEquityUnits) {
        long low = input.markPriceTicks();
        long high = expandShortHigh(input, targetEquityUnits);
        if (high == 0L) {
            return 0L;
        }
        while (low + 1L < high) {
            long mid = low + ((high - low) >>> 1);
            if (equityAt(input, mid) <= targetEquityUnits) {
                high = mid;
            } else {
                low = mid;
            }
        }
        return high;
    }

    private long expandShortHigh(LiquidationPricingInput input, long targetEquityUnits) {
        long high = input.markPriceTicks();
        while (equityAt(input, high) > targetEquityUnits) {
            if (high > Long.MAX_VALUE / 2L) {
                return equityAt(input, Long.MAX_VALUE) <= targetEquityUnits ? Long.MAX_VALUE : 0L;
            }
            high = Math.multiplyExact(high, 2L);
        }
        return high;
    }

    private long equityAt(LiquidationPricingInput input, long priceTicks) {
        try {
            long delta = PerpetualContractMath.unrealizedPnlUnits(input.contractType(),
                    input.signedQuantitySteps(), input.markPriceTicks(), priceTicks,
                    input.notionalMultiplierUnits(), input.priceTickUnits(), input.settleScaleUnits());
            return Math.addExact(input.equityUnits(), delta);
        } catch (ArithmeticException ex) {
            return isAdverse(input, priceTicks) ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
    }

    private boolean isAdverse(LiquidationPricingInput input, long priceTicks) {
        return input.signedQuantitySteps() > 0
                ? priceTicks < input.markPriceTicks()
                : priceTicks > input.markPriceTicks();
    }
}
