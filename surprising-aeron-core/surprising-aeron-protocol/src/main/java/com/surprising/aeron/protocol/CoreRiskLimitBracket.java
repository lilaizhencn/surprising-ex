package com.surprising.aeron.protocol;

/** 有序风险档的金额上界及金融参数；编号和下界由所在列表的位置与前档上界确定。 */
public record CoreRiskLimitBracket(
        long notionalCapUnits,
        long maxLeveragePpm,
        long initialMarginRatePpm,
        long maintenanceMarginRatePpm,
        long optionMarginFactorPpm) {

    public CoreRiskLimitBracket {
        if (notionalCapUnits <= 0
                || initialMarginRatePpm <= 0
                || initialMarginRatePpm > 1_000_000L || maintenanceMarginRatePpm <= 0
                || maintenanceMarginRatePpm > 1_000_000L) {
            throw new IllegalArgumentException("invalid risk limit bracket");
        }
    }

    public CoreRiskLimitBracket(
            long notionalCapUnits,
            long maxLeveragePpm,
            long initialMarginRatePpm,
            long maintenanceMarginRatePpm) {
        this(notionalCapUnits, maxLeveragePpm,
                initialMarginRatePpm, maintenanceMarginRatePpm, 1_000_000L);
    }
}
