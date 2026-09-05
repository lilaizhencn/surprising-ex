package com.surprising.aeron.service.state;

import static com.surprising.aeron.service.state.CoreArithmetic.*;

import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreRiskLimitBracket;
import com.surprising.instrument.api.math.PerpetualContractMath;
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
            return OptionContractMath.openingMarginUnits(instrument, side, priceTicks, quantitySteps,
                initialMarginRatePpm, indexPriceTicks, forwardPriceTicks, optionMarginFactorPpm);
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
            return OptionContractMath.maintenanceMarginUnits(instrument, signedQuantitySteps, markPriceTicks,
                indexPriceTicks, forwardPriceTicks, bracket, maintenanceMarginRatePpm);
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
                ? OptionContractMath.optionPremiumUnits(instrument, priceTicks, quantitySteps)
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
            return OptionContractMath.optionPremiumUnits(instrument, priceTicks, quantitySteps);
        }
        return PerpetualContractMath.notionalUnits(instrument.contractType(), quantitySteps, priceTicks,
                instrument.notionalMultiplierUnits(), instrument.priceTickUnits(), instrument.settleScaleUnits());
    }

    static long riskNotionalUnits(CoreInstrumentState instrument, long quantitySteps, long referencePriceTicks) {
        if (quantitySteps <= 0) return 0;
        return instrument.contractType().isOption()
                ? OptionContractMath.optionPremiumUnits(instrument, referencePriceTicks, quantitySteps)
                : notionalUnits(instrument, quantitySteps, referencePriceTicks);
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
        return instrument.contractType().isInverse()
                ? InverseContractMath.weightedEntryPrice(instrument, currentAbs, currentPrice, addedAbs, addedPrice)
                : LinearContractMath.weightedEntryPrice(instrument, currentAbs, currentPrice, addedAbs, addedPrice);
    }

}
