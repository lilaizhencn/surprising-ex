package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CoreOrderSide;

final class OptionFillCalculator {
    private OptionFillCalculator() {}

    static long openingMarginForFill(CoreInstrumentState instrument,
                                             long projectedQuantitySteps,
                                             long signedFillSteps,
                                             long openSteps,
                                             long priceTicks,
                                             long leveragePpm,
                                             MarkPriceRuntime riskMark) {
        if (openSteps == 0 || signedFillSteps > 0) return 0;
        long indexPriceTicks = riskMark == null ? 0 : riskMark.indexPriceTicks();
        long projectedNotional = CoreContractMath.riskNotionalUnits(instrument,
                Math.absExact(projectedQuantitySteps), indexPriceTicks);
        var bracket = CoreContractMath.maintenanceRiskBracket(instrument, projectedNotional);
        long bracketRate = bracket.initialMarginRatePpm();
        long leverageRate = CoreContractMath.initialMarginRateFromLeverage(leveragePpm);
        long effectiveRate = bracketRate;
        long marginPriceTicks = riskMark.markPriceTicks();
        return CoreContractMath.openingMarginUnits(instrument,
                signedFillSteps > 0 ? CoreOrderSide.BUY : CoreOrderSide.SELL,
                marginPriceTicks, openSteps, effectiveRate,
                indexPriceTicks, riskMark == null ? 0 : riskMark.forwardPriceTicks(),
                bracket.optionMarginFactorPpm());
    }

    static void requireRiskMark(MarkPriceRuntime riskMark) {
        if (riskMark == null || riskMark.indexPriceTicks() <= 0 || riskMark.forwardPriceTicks() <= 0) {
            throw new CoreStateRejectedException("OPTION_RISK_PRICE_MISSING",
                    "option fill requires index and same-expiry forward prices");
        }
    }

    static long premiumMarginFunding(CoreInstrumentState instrument, long premiumDelta,
                                     long openSteps, long marginIncrease, long fillPriceTicks) {
        return premiumDelta > 0 && openSteps > 0
                ? Math.min(marginIncrease,
                        OptionContractMath.optionPremiumUnits(instrument, fillPriceTicks, openSteps)) : 0;
    }
}
