package com.surprising.risk.provider.service;

import com.surprising.instrument.api.math.PerpetualContractMath;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.risk.api.model.RiskStatus;

public final class RiskMath {

    public static final long PPM = 1_000_000L;
    public static final long INFINITE_MARGIN_RATIO = Long.MAX_VALUE;

    private RiskMath() {
    }

    public static long equity(long walletBalanceUnits, long unrealizedPnlUnits) {
        return Math.addExact(walletBalanceUnits, unrealizedPnlUnits);
    }

    public static long marginRatioPpm(long maintenanceMarginUnits, long equityUnits) {
        if (maintenanceMarginUnits <= 0) {
            return 0L;
        }
        if (equityUnits <= 0) {
            return INFINITE_MARGIN_RATIO;
        }
        try {
            return Math.multiplyExact(maintenanceMarginUnits, PPM) / equityUnits;
        } catch (ArithmeticException ex) {
            return INFINITE_MARGIN_RATIO;
        }
    }

    public static RiskStatus status(long marginRatioPpm, long warningPpm, long liquidationPpm) {
        if (marginRatioPpm >= liquidationPpm) {
            return RiskStatus.LIQUIDATION;
        }
        if (marginRatioPpm >= warningPpm) {
            return RiskStatus.WARNING;
        }
        return RiskStatus.NORMAL;
    }

    public static long notionalUnits(ContractType contractType,
                                     long signedQuantitySteps,
                                     long markPriceTicks,
                                     long notionalMultiplierUnits,
                                     long priceTickUnits,
                                     long settleScaleUnits) {
        return PerpetualContractMath.notionalUnits(contractType, signedQuantitySteps, markPriceTicks,
                notionalMultiplierUnits, priceTickUnits, settleScaleUnits);
    }

    public static long unrealizedPnlUnits(ContractType contractType,
                                          long signedQuantitySteps,
                                          long entryPriceTicks,
                                          long markPriceTicks,
                                          long notionalMultiplierUnits,
                                          long priceTickUnits,
                                          long settleScaleUnits) {
        return PerpetualContractMath.unrealizedPnlUnits(contractType, signedQuantitySteps, entryPriceTicks,
                markPriceTicks, notionalMultiplierUnits, priceTickUnits, settleScaleUnits);
    }

    public static long maintenanceMarginUnits(ContractType contractType,
                                              long signedQuantitySteps,
                                              long markPriceTicks,
                                              long notionalMultiplierUnits,
                                              long priceTickUnits,
                                              long settleScaleUnits,
                                              long maintenanceMarginRatePpm) {
        return PerpetualContractMath.maintenanceMarginUnits(contractType, signedQuantitySteps, markPriceTicks,
                notionalMultiplierUnits, priceTickUnits, settleScaleUnits, maintenanceMarginRatePpm);
    }
}
