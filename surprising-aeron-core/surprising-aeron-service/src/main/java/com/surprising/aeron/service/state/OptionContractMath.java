package com.surprising.aeron.service.state;

import static com.surprising.aeron.service.state.CoreArithmetic.*;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreRiskLimitBracket;
import com.surprising.instrument.api.model.OptionType;
import java.math.BigInteger;

final class OptionContractMath {

    private OptionContractMath() {
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

    static long optionOutOfMoneyTicks(CoreInstrumentState instrument, long forwardPriceTicks) {
        return instrument.optionType() == OptionType.CALL
                ? Math.max(0, Math.subtractExact(instrument.strikePriceTicks(), forwardPriceTicks))
                : Math.max(0, Math.subtractExact(forwardPriceTicks, instrument.strikePriceTicks()));
    }

    static void requireOptionRiskPrices(long indexPriceTicks, long forwardPriceTicks) {
        if (indexPriceTicks <= 0 || forwardPriceTicks <= 0) {
            throw new CoreStateRejectedException("OPTION_RISK_PRICE_MISSING",
                    "option margin requires index and same-expiry forward prices");
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
    static long openingMarginUnits(CoreInstrumentState instrument, CoreOrderSide side, long priceTicks,
                long quantitySteps, long initialMarginRatePpm, long indexPriceTicks, long forwardPriceTicks,
                long optionMarginFactorPpm) {
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
    static long maintenanceMarginUnits(CoreInstrumentState instrument, long signedQuantitySteps,
                long markPriceTicks, long indexPriceTicks, long forwardPriceTicks, CoreRiskLimitBracket bracket,
                long maintenanceMarginRatePpm) {
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
}
