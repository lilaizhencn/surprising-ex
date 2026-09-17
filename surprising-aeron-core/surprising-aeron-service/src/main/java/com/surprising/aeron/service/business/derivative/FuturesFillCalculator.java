package com.surprising.aeron.service.business.derivative;

import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.service.state.CoreInstrumentState;
import com.surprising.aeron.service.state.MarkPriceRuntime;
import com.surprising.aeron.service.state.math.CoreContractMath;

/** Calculates opening margin for perpetual and delivery fills. */
public final class FuturesFillCalculator {
    private FuturesFillCalculator() {
    }

    public static long openingMarginForFill(CoreInstrumentState instrument,
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
