package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.instrument.api.math.PerpetualContractMath;
import java.math.BigInteger;

final class CoreContractMath {

    private static final BigInteger PPM = BigInteger.valueOf(1_000_000L);

    private CoreContractMath() {
    }

    static long openingMarginUnits(
            CoreInstrumentState instrument,
            CoreOrderSide side,
            long priceTicks,
            long quantitySteps) {
        return openingMarginUnits(instrument, side, priceTicks, quantitySteps,
                instrument.initialMarginRatePpm());
    }

    static long openingMarginUnits(
            CoreInstrumentState instrument,
            CoreOrderSide side,
            long priceTicks,
            long quantitySteps,
            long initialMarginRatePpm) {
        if (quantitySteps <= 0) {
            return 0;
        }
        if (instrument.contractType().isOption()) {
            long premium = optionPremiumUnits(instrument, priceTicks, quantitySteps);
            if (side == CoreOrderSide.BUY) {
                return 0;
            }
            BigInteger riskNumerator = big(instrument.strikePriceTicks())
                    .multiply(big(quantitySteps))
                    .multiply(big(instrument.notionalMultiplierUnits()))
                    .multiply(big(initialMarginRatePpm));
            return Math.addExact(premium, divideCeiling(riskNumerator, PPM));
        }
        return PerpetualContractMath.initialMarginUnits(instrument.contractType(), quantitySteps, priceTicks,
                instrument.notionalMultiplierUnits(), instrument.priceTickUnits(), instrument.settleScaleUnits(),
                initialMarginRatePpm);
    }

    static long maintenanceMarginUnits(
            CoreInstrumentState instrument,
            long signedQuantitySteps,
            long markPriceTicks) {
        if (instrument.contractType().isOption()) {
            if (signedQuantitySteps > 0) {
                return 0;
            }
            BigInteger numerator = big(instrument.strikePriceTicks())
                    .multiply(big(Math.absExact(signedQuantitySteps)))
                    .multiply(big(instrument.notionalMultiplierUnits()))
                    .multiply(big(instrument.maintenanceMarginRatePpm()));
            return divideCeiling(numerator, PPM);
        }
        return PerpetualContractMath.maintenanceMarginUnits(instrument.contractType(), signedQuantitySteps,
                markPriceTicks, instrument.notionalMultiplierUnits(), instrument.priceTickUnits(),
                instrument.settleScaleUnits(), instrument.maintenanceMarginRatePpm());
    }

    static long pnlUnits(
            CoreInstrumentState instrument,
            long signedQuantitySteps,
            long entryPriceTicks,
            long exitPriceTicks) {
        return PerpetualContractMath.unrealizedPnlUnits(instrument.contractType(), signedQuantitySteps,
                entryPriceTicks, exitPriceTicks, instrument.notionalMultiplierUnits(),
                instrument.priceTickUnits(), instrument.settleScaleUnits());
    }

    static long optionPremiumUnits(
            CoreInstrumentState instrument,
            long priceTicks,
            long quantitySteps) {
        if (!instrument.contractType().isOption() || priceTicks <= 0 || quantitySteps <= 0) {
            throw new IllegalArgumentException("invalid option premium input");
        }
        return big(priceTicks).multiply(big(quantitySteps))
                .multiply(big(instrument.notionalMultiplierUnits())).longValueExact();
    }

    static long feeDeltaUnits(
            CoreInstrumentState instrument,
            long priceTicks,
            long quantitySteps,
            boolean taker) {
        long rate = taker ? instrument.takerFeeRatePpm() : instrument.makerFeeRatePpm();
        if (rate == 0) {
            return 0;
        }
        long notional = instrument.contractType().isOption()
                ? optionPremiumUnits(instrument, priceTicks, quantitySteps)
                : PerpetualContractMath.notionalUnits(instrument.contractType(), quantitySteps, priceTicks,
                instrument.notionalMultiplierUnits(), instrument.priceTickUnits(), instrument.settleScaleUnits());
        long fee = divideCeiling(big(notional).multiply(big(Math.absExact(rate))), PPM);
        return rate > 0 ? Math.negateExact(fee) : fee;
    }

    static long fundingDeltaUnits(
            CoreInstrumentState instrument,
            long signedQuantitySteps,
            long markPriceTicks,
            long fundingRatePpm) {
        long notional = PerpetualContractMath.notionalUnits(instrument.contractType(), signedQuantitySteps,
                markPriceTicks, instrument.notionalMultiplierUnits(), instrument.priceTickUnits(),
                instrument.settleScaleUnits());
        BigInteger signedPayment = big(notional).multiply(big(fundingRatePpm))
                .multiply(BigInteger.valueOf(Long.signum(signedQuantitySteps))).divide(PPM);
        return signedPayment.negate().longValueExact();
    }

    static long weightedEntryPrice(
            CoreInstrumentState instrument,
            long currentAbs,
            long currentPrice,
            long addedAbs,
            long addedPrice) {
        if (!instrument.contractType().isInverse()) {
            BigInteger value = big(currentAbs).multiply(big(currentPrice))
                    .add(big(addedAbs).multiply(big(addedPrice)));
            return value.divide(big(Math.addExact(currentAbs, addedAbs))).longValueExact();
        }
        BigInteger numerator = big(Math.addExact(currentAbs, addedAbs))
                .multiply(big(currentPrice)).multiply(big(addedPrice));
        BigInteger denominator = big(currentAbs).multiply(big(addedPrice))
                .add(big(addedAbs).multiply(big(currentPrice)));
        return Math.max(1, divideRounded(numerator, denominator));
    }

    private static long divideCeiling(BigInteger numerator, BigInteger denominator) {
        BigInteger[] values = numerator.divideAndRemainder(denominator);
        return (values[1].signum() == 0 ? values[0] : values[0].add(BigInteger.ONE)).longValueExact();
    }

    private static long divideRounded(BigInteger numerator, BigInteger denominator) {
        BigInteger[] values = numerator.divideAndRemainder(denominator);
        BigInteger result = values[1].shiftLeft(1).compareTo(denominator) >= 0
                ? values[0].add(BigInteger.ONE) : values[0];
        return result.longValueExact();
    }

    private static BigInteger big(long value) {
        return BigInteger.valueOf(value);
    }
}
