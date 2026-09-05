package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CoreOrderSide;

final class FuturesFillCalculator {
    private FuturesFillCalculator() {}

    static long openingMarginForFill(CoreInstrumentState instrument,
                                             long projectedQuantitySteps,
                                             long signedFillSteps,
                                             long openSteps,
                                             long priceTicks,
                                             long leveragePpm,
                                             MarkPriceRuntime riskMark) {
        if (openSteps == 0) return 0;
        long indexPriceTicks = riskMark == null ? 0 : riskMark.indexPriceTicks();
        long projectedNotional = CoreContractMath.riskNotionalUnits(instrument,
                Math.absExact(projectedQuantitySteps), priceTicks);
        var bracket = CoreContractMath.maintenanceRiskBracket(instrument, projectedNotional);
        long bracketRate = bracket.initialMarginRatePpm();
        long leverageRate = CoreContractMath.initialMarginRateFromLeverage(leveragePpm);
        long effectiveRate = Math.max(Math.max(instrument.initialMarginRatePpm(), bracketRate), leverageRate);
        long marginPriceTicks = priceTicks;
        return CoreContractMath.openingMarginUnits(instrument,
                signedFillSteps > 0 ? CoreOrderSide.BUY : CoreOrderSide.SELL,
                marginPriceTicks, openSteps, effectiveRate,
                indexPriceTicks, riskMark == null ? 0 : riskMark.forwardPriceTicks(),
                bracket.optionMarginFactorPpm());
    }
}
