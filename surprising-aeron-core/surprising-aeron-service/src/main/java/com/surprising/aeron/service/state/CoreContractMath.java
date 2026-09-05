package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreRiskLimitBracket;
import com.surprising.instrument.api.math.PerpetualContractMath;
import com.surprising.instrument.api.model.OptionType;
import java.math.BigInteger;

final class CoreContractMath {

    private static final BigInteger PPM = BigInteger.valueOf(1_000_000L);
    private static final long LEVERAGE_RATE_NUMERATOR = 1_000_000_000_000L;

    private CoreContractMath() {
    }

    static long openingMarginUnits(
            CoreInstrumentState instrument,
            CoreOrderSide side,
            long priceTicks,
            long quantitySteps,
            long indexPriceTicks,
            long forwardPriceTicks) {
        if (quantitySteps <= 0 || instrument.contractType().isOption() && side == CoreOrderSide.BUY) {
            return 0;
        }
        CoreRiskLimitBracket bracket = instrument.contractType() != com.surprising.instrument.api.model.ContractType.SPOT
                ? riskBracket(instrument, riskNotionalUnits(instrument, quantitySteps,
                instrument.contractType().isOption() ? indexPriceTicks : priceTicks))
                : null;
        long initialMarginRatePpm = bracket != null
                ? bracket.initialMarginRatePpm()
                : instrument.initialMarginRatePpm();
        return openingMarginUnits(instrument, side, priceTicks, quantitySteps,
                initialMarginRatePpm, indexPriceTicks, forwardPriceTicks,
                bracket == null ? 1_000_000L : bracket.optionMarginFactorPpm());
    }

    static long initialMarginRateFromLeverage(long leveragePpm) {
        if (leveragePpm <= 0) {
            throw new IllegalArgumentException("leverage must be positive");
        }
        long quotient = LEVERAGE_RATE_NUMERATOR / leveragePpm;
        return LEVERAGE_RATE_NUMERATOR % leveragePpm == 0 ? quotient : Math.addExact(quotient, 1);
    }

    static long scaledFloorCapped(
            long value, long multiplier, long divisor, long floor, long cap) {
        if (value < 0 || multiplier < 0 || divisor <= 0 || floor < 0 || cap < 0) {
            throw new IllegalArgumentException("invalid scaled limit input");
        }
        try {
            long scaled = Math.multiplyExact(value, multiplier) / divisor;
            return Math.min(Math.max(scaled, floor), cap);
        } catch (ArithmeticException overflow) {
            return big(value).multiply(big(multiplier)).divide(big(divisor))
                    .max(big(floor)).min(big(cap)).longValueExact();
        }
    }

    static long openingMarginUnits(
            CoreInstrumentState instrument,
            CoreOrderSide side,
            long priceTicks,
            long quantitySteps,
            long initialMarginRatePpm,
            long indexPriceTicks,
            long forwardPriceTicks) {
        return openingMarginUnits(instrument, side, priceTicks, quantitySteps, initialMarginRatePpm,
                indexPriceTicks, forwardPriceTicks, 1_000_000L);
    }

    static long openingMarginUnits(
            CoreInstrumentState instrument,
            CoreOrderSide side,
            long priceTicks,
            long quantitySteps,
            long initialMarginRatePpm,
            long indexPriceTicks,
            long forwardPriceTicks,
            long optionMarginFactorPpm) {
        if (quantitySteps <= 0) {
            return 0;
        }
        if (instrument.contractType().isOption()) {
            if (side == CoreOrderSide.BUY) {
                return 0;
            }
            requireOptionRiskPrices(indexPriceTicks, forwardPriceTicks);
            long premium = optionPremiumUnits(instrument, priceTicks, quantitySteps);
            long outOfMoneyTicks = optionOutOfMoneyTicks(instrument, forwardPriceTicks);
            long outOfMoneyRatePpm = scalePpmFloor(outOfMoneyTicks, forwardPriceTicks);
            long riskRatePpm = Math.max(instrument.initialMarginRatePpm(),
                    Math.max(0, Math.subtractExact(initialMarginRatePpm, outOfMoneyRatePpm)));
            long riskTicks = scalePpmSquaredCeiling(indexPriceTicks, riskRatePpm, optionMarginFactorPpm);
            long risk = optionPremiumUnits(instrument, riskTicks, quantitySteps);
            return Math.addExact(premium, risk);
        }
        return PerpetualContractMath.initialMarginUnits(instrument.contractType(), quantitySteps, priceTicks,
                instrument.notionalMultiplierUnits(), instrument.priceTickUnits(), instrument.settleScaleUnits(),
                initialMarginRatePpm);
    }

    static long maintenanceMarginUnits(
            CoreInstrumentState instrument,
            long signedQuantitySteps,
            long markPriceTicks,
            long indexPriceTicks,
            long forwardPriceTicks) {
        CoreRiskLimitBracket bracket = instrument.contractType() != com.surprising.instrument.api.model.ContractType.SPOT
                ? maintenanceRiskBracket(instrument, riskNotionalUnits(instrument,
                Math.absExact(signedQuantitySteps), instrument.contractType().isOption()
                        ? indexPriceTicks : markPriceTicks))
                : null;
        long maintenanceMarginRatePpm = bracket != null
                ? bracket.maintenanceMarginRatePpm()
                : instrument.maintenanceMarginRatePpm();
        if (instrument.contractType().isOption()) {
            if (signedQuantitySteps > 0) {
                return 0;
            }
            requireOptionRiskPrices(indexPriceTicks, forwardPriceTicks);
            long quantity = Math.absExact(signedQuantitySteps);
            long factor = bracket.optionMarginFactorPpm();
            long underlyingRiskTicks = scalePpmSquaredCeiling(
                    indexPriceTicks, maintenanceMarginRatePpm, factor);
            long riskTicks = instrument.optionType() == OptionType.PUT
                    ? Math.max(underlyingRiskTicks,
                    scalePpmSquaredCeiling(markPriceTicks, maintenanceMarginRatePpm, factor))
                    : underlyingRiskTicks;
            return Math.addExact(optionPremiumUnits(instrument, markPriceTicks, quantity),
                    optionPremiumUnits(instrument, riskTicks, quantity));
        }
        return PerpetualContractMath.maintenanceMarginUnits(instrument.contractType(), signedQuantitySteps,
                markPriceTicks, instrument.notionalMultiplierUnits(), instrument.priceTickUnits(),
                instrument.settleScaleUnits(), maintenanceMarginRatePpm);
    }

    static long optionSellOpenOrderMarginUnits(
            CoreInstrumentState instrument,
            long orderPriceTicks,
            long markPriceTicks,
            long quantitySteps,
            long indexPriceTicks,
            long forwardPriceTicks,
            CoreRiskLimitBracket bracket) {
        if (!instrument.contractType().isOption() || bracket == null || quantitySteps <= 0) {
            throw new IllegalArgumentException("invalid option sell-open margin input");
        }
        long positionMargin = openingMarginUnits(instrument, CoreOrderSide.SELL, markPriceTicks,
                quantitySteps, bracket.initialMarginRatePpm(), indexPriceTicks, forwardPriceTicks,
                bracket.optionMarginFactorPpm());
        long orderPremium = optionPremiumUnits(instrument, orderPriceTicks, quantitySteps);
        long marginLessPremium = Math.max(0, Math.subtractExact(positionMargin, orderPremium));
        long minimumRiskTicks = scalePpmCeiling(indexPriceTicks, instrument.initialMarginRatePpm());
        long minimumOpenMargin = optionPremiumUnits(instrument, minimumRiskTicks, quantitySteps);
        return Math.max(marginLessPremium, minimumOpenMargin);
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
        CoreRiskLimitBracket selected = null;
        for (CoreRiskLimitBracket candidate : instrument.riskLimitBrackets()) {
            if (candidate.notionalFloorUnits() <= notionalUnits
                    && (selected == null
                    || candidate.notionalFloorUnits() > selected.notionalFloorUnits())) {
                selected = candidate;
            }
        }
        if (selected == null) {
            throw new CoreStateRejectedException("RISK_BRACKET_EXCEEDED",
                    "position notional has no risk bracket");
        }
        return selected;
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

    static long optionMarketValueUnits(CoreInstrumentState instrument, long signedQuantitySteps,
                                       long markPriceTicks) {
        long value = optionPremiumUnits(instrument, markPriceTicks, Math.absExact(signedQuantitySteps));
        return signedQuantitySteps > 0 ? value : Math.negateExact(value);
    }

    private static long optionOutOfMoneyTicks(CoreInstrumentState instrument, long forwardPriceTicks) {
        return instrument.optionType() == OptionType.CALL
                ? Math.max(0, Math.subtractExact(instrument.strikePriceTicks(), forwardPriceTicks))
                : Math.max(0, Math.subtractExact(forwardPriceTicks, instrument.strikePriceTicks()));
    }

    private static void requireOptionRiskPrices(long indexPriceTicks, long forwardPriceTicks) {
        if (indexPriceTicks <= 0 || forwardPriceTicks <= 0) {
            throw new CoreStateRejectedException("OPTION_RISK_PRICE_MISSING",
                    "option margin requires index and same-expiry forward prices");
        }
    }

    private static long scalePpmCeiling(long value, long ratePpm) {
        try {
            return divideCeiling(Math.multiplyExact(value, ratePpm), 1_000_000L);
        } catch (ArithmeticException overflow) {
            return divideCeiling(big(value).multiply(big(ratePpm)), PPM);
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

    static long riskNotionalUnits(CoreInstrumentState instrument, long quantitySteps, long referencePriceTicks) {
        if (quantitySteps <= 0) return 0;
        return instrument.contractType().isOption()
                ? optionPremiumUnits(instrument, referencePriceTicks, quantitySteps)
                : notionalUnits(instrument, quantitySteps, referencePriceTicks);
    }

    private static long scalePpmFloor(long value, long divisorValue) {
        try {
            return Math.multiplyExact(value, 1_000_000L) / divisorValue;
        } catch (ArithmeticException overflow) {
            return big(value).multiply(PPM).divide(big(divisorValue)).longValueExact();
        }
    }

    private static long scalePpmSquaredCeiling(long value, long firstRatePpm, long secondRatePpm) {
        try {
            long numerator = Math.multiplyExact(Math.multiplyExact(value, firstRatePpm), secondRatePpm);
            return divideCeiling(numerator, 1_000_000_000_000L);
        } catch (ArithmeticException overflow) {
            return divideCeiling(big(value).multiply(big(firstRatePpm)).multiply(big(secondRatePpm)),
                    PPM.multiply(PPM));
        }
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
