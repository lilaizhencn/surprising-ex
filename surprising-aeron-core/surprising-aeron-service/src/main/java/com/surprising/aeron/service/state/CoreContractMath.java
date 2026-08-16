package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreRiskLimitBracket;
import com.surprising.instrument.api.math.PerpetualContractMath;
import com.surprising.instrument.api.model.OptionType;
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
        long initialMarginRatePpm = instrument.contractType() != com.surprising.instrument.api.model.ContractType.SPOT
                ? riskBracket(instrument, notionalUnits(instrument, quantitySteps, priceTicks)).initialMarginRatePpm()
                : instrument.initialMarginRatePpm();
        return openingMarginUnits(instrument, side, priceTicks, quantitySteps,
                initialMarginRatePpm);
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
            long risk;
            try {
                long numerator = Math.multiplyExact(instrument.strikePriceTicks(), quantitySteps);
                numerator = Math.multiplyExact(numerator, instrument.notionalMultiplierUnits());
                numerator = Math.multiplyExact(numerator, initialMarginRatePpm);
                risk = divideCeiling(numerator, 1_000_000L);
            } catch (ArithmeticException overflow) {
                BigInteger riskNumerator = big(instrument.strikePriceTicks())
                        .multiply(big(quantitySteps))
                        .multiply(big(instrument.notionalMultiplierUnits()))
                        .multiply(big(initialMarginRatePpm));
                risk = divideCeiling(riskNumerator, PPM);
            }
            return Math.addExact(premium, risk);
        }
        return PerpetualContractMath.initialMarginUnits(instrument.contractType(), quantitySteps, priceTicks,
                instrument.notionalMultiplierUnits(), instrument.priceTickUnits(), instrument.settleScaleUnits(),
                initialMarginRatePpm);
    }

    static long maintenanceMarginUnits(
            CoreInstrumentState instrument,
            long signedQuantitySteps,
            long markPriceTicks) {
        long maintenanceMarginRatePpm = instrument.contractType() != com.surprising.instrument.api.model.ContractType.SPOT
                ? maintenanceRiskBracket(instrument, notionalUnits(instrument, Math.absExact(signedQuantitySteps), markPriceTicks))
                .maintenanceMarginRatePpm()
                : instrument.maintenanceMarginRatePpm();
        if (instrument.contractType().isOption()) {
            if (signedQuantitySteps > 0) {
                return 0;
            }
            long quantity = Math.absExact(signedQuantitySteps);
            try {
                long numerator = Math.multiplyExact(instrument.strikePriceTicks(), quantity);
                numerator = Math.multiplyExact(numerator, instrument.notionalMultiplierUnits());
                numerator = Math.multiplyExact(numerator, maintenanceMarginRatePpm);
                return divideCeiling(numerator, 1_000_000L);
            } catch (ArithmeticException overflow) {
                BigInteger numerator = big(instrument.strikePriceTicks())
                        .multiply(big(quantity))
                        .multiply(big(instrument.notionalMultiplierUnits()))
                        .multiply(big(maintenanceMarginRatePpm));
                return divideCeiling(numerator, PPM);
            }
        }
        return PerpetualContractMath.maintenanceMarginUnits(instrument.contractType(), signedQuantitySteps,
                markPriceTicks, instrument.notionalMultiplierUnits(), instrument.priceTickUnits(),
                instrument.settleScaleUnits(), maintenanceMarginRatePpm);
    }

    static CoreRiskLimitBracket riskBracket(CoreInstrumentState instrument, long notionalUnits) {
        CoreRiskLimitBracket bracket = bracketForNotional(instrument, notionalUnits);
        if (notionalUnits > bracket.notionalCapUnits()) {
            throw new CoreStateRejectedException("RISK_BRACKET_EXCEEDED",
                    "position notional exceeds instrument risk brackets");
        }
        return bracket;
    }

    static CoreRiskLimitBracket maintenanceRiskBracket(CoreInstrumentState instrument, long notionalUnits) {
        return bracketForNotional(instrument, notionalUnits);
    }

    private static CoreRiskLimitBracket bracketForNotional(CoreInstrumentState instrument, long notionalUnits) {
        if (notionalUnits < 0) {
            throw new IllegalArgumentException("notional must not be negative");
        }
        return instrument.riskLimitBrackets().stream()
                .filter(value -> value.notionalFloorUnits() <= notionalUnits)
                .max(java.util.Comparator.comparingLong(CoreRiskLimitBracket::notionalFloorUnits))
                .orElseThrow(() -> new CoreStateRejectedException("RISK_BRACKET_EXCEEDED",
                        "position notional has no risk bracket"));
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
        try {
            return Math.multiplyExact(Math.multiplyExact(priceTicks, quantitySteps),
                    instrument.notionalMultiplierUnits());
        } catch (ArithmeticException overflow) {
            return big(priceTicks).multiply(big(quantitySteps))
                    .multiply(big(instrument.notionalMultiplierUnits())).longValueExact();
        }
    }

    static long optionSettlementCashUnits(
            CoreInstrumentState instrument,
            long underlyingSettlementPriceTicks) {
        if (!instrument.contractType().isOption() || underlyingSettlementPriceTicks <= 0
                || instrument.optionType() == null || instrument.strikePriceTicks() <= 0) {
            throw new IllegalArgumentException("invalid option settlement input");
        }
        BigInteger settlement = big(underlyingSettlementPriceTicks);
        BigInteger strike = big(instrument.strikePriceTicks());
        BigInteger intrinsic = instrument.optionType() == OptionType.CALL
                ? settlement.subtract(strike).max(BigInteger.ZERO)
                : strike.subtract(settlement).max(BigInteger.ZERO);
        return intrinsic.multiply(big(instrument.notionalMultiplierUnits())).longValueExact();
    }

    static long feeDeltaUnits(
            CoreInstrumentState instrument,
            long priceTicks,
            long quantitySteps,
            long feeRatePpm) {
        if (feeRatePpm == 0) {
            return 0;
        }
        long notional = instrument.contractType() == com.surprising.instrument.api.model.ContractType.SPOT
                ? Math.multiplyExact(priceTicks, quantitySteps)
                : instrument.contractType().isOption()
                ? optionPremiumUnits(instrument, priceTicks, quantitySteps)
                : PerpetualContractMath.notionalUnits(instrument.contractType(), quantitySteps, priceTicks,
                instrument.notionalMultiplierUnits(), instrument.priceTickUnits(), instrument.settleScaleUnits());
        long fee;
        long absoluteRate = Math.absExact(feeRatePpm);
        try {
            fee = divideCeiling(Math.multiplyExact(notional, absoluteRate), 1_000_000L);
        } catch (ArithmeticException overflow) {
            fee = divideCeiling(big(notional).multiply(big(absoluteRate)), PPM);
        }
        return feeRatePpm > 0 ? Math.negateExact(fee) : fee;
    }

    static long notionalUnits(CoreInstrumentState instrument, long quantitySteps, long priceTicks) {
        if (quantitySteps <= 0) return 0;
        if (instrument.contractType().isOption()) {
            return optionPremiumUnits(instrument, priceTicks, quantitySteps);
        }
        return PerpetualContractMath.notionalUnits(instrument.contractType(), quantitySteps, priceTicks,
                instrument.notionalMultiplierUnits(), instrument.priceTickUnits(), instrument.settleScaleUnits());
    }

    static long fundingDeltaUnits(
            CoreInstrumentState instrument,
            long signedQuantitySteps,
            long markPriceTicks,
            long fundingRatePpm) {
        long notional = PerpetualContractMath.notionalUnits(instrument.contractType(), signedQuantitySteps,
                markPriceTicks, instrument.notionalMultiplierUnits(), instrument.priceTickUnits(),
                instrument.settleScaleUnits());
        try {
            long numerator = Math.multiplyExact(notional, fundingRatePpm);
            numerator = Math.multiplyExact(numerator, Long.signum(signedQuantitySteps));
            return Math.negateExact(numerator / 1_000_000L);
        } catch (ArithmeticException overflow) {
            BigInteger signedPayment = big(notional).multiply(big(fundingRatePpm))
                    .multiply(BigInteger.valueOf(Long.signum(signedQuantitySteps))).divide(PPM);
            return signedPayment.negate().longValueExact();
        }
    }

    static long weightedEntryPrice(
            CoreInstrumentState instrument,
            long currentAbs,
            long currentPrice,
            long addedAbs,
            long addedPrice) {
        if (!instrument.contractType().isInverse()) {
            try {
                long value = Math.addExact(Math.multiplyExact(currentAbs, currentPrice),
                        Math.multiplyExact(addedAbs, addedPrice));
                return value / Math.addExact(currentAbs, addedAbs);
            } catch (ArithmeticException overflow) {
                BigInteger value = big(currentAbs).multiply(big(currentPrice))
                        .add(big(addedAbs).multiply(big(addedPrice)));
                return value.divide(big(Math.addExact(currentAbs, addedAbs))).longValueExact();
            }
        }
        try {
            long numerator = Math.multiplyExact(Math.addExact(currentAbs, addedAbs), currentPrice);
            numerator = Math.multiplyExact(numerator, addedPrice);
            long denominator = Math.addExact(Math.multiplyExact(currentAbs, addedPrice),
                    Math.multiplyExact(addedAbs, currentPrice));
            return Math.max(1, divideRounded(numerator, denominator));
        } catch (ArithmeticException overflow) {
            BigInteger numerator = big(Math.addExact(currentAbs, addedAbs))
                    .multiply(big(currentPrice)).multiply(big(addedPrice));
            BigInteger denominator = big(currentAbs).multiply(big(addedPrice))
                    .add(big(addedAbs).multiply(big(currentPrice)));
            return Math.max(1, divideRounded(numerator, denominator));
        }
    }

    private static long divideCeiling(BigInteger numerator, BigInteger denominator) {
        BigInteger[] values = numerator.divideAndRemainder(denominator);
        return (values[1].signum() == 0 ? values[0] : values[0].add(BigInteger.ONE)).longValueExact();
    }

    private static long divideCeiling(long numerator, long denominator) {
        long quotient = numerator / denominator;
        return numerator % denominator == 0 ? quotient : Math.addExact(quotient, 1);
    }

    private static long divideRounded(BigInteger numerator, BigInteger denominator) {
        BigInteger[] values = numerator.divideAndRemainder(denominator);
        BigInteger result = values[1].shiftLeft(1).compareTo(denominator) >= 0
                ? values[0].add(BigInteger.ONE) : values[0];
        return result.longValueExact();
    }

    private static long divideRounded(long numerator, long denominator) {
        long quotient = numerator / denominator;
        long remainder = numerator % denominator;
        boolean roundsUp = remainder > denominator / 2
                || (denominator % 2 == 0 && remainder == denominator / 2);
        return roundsUp ? Math.addExact(quotient, 1) : quotient;
    }

    private static BigInteger big(long value) {
        return BigInteger.valueOf(value);
    }
}
