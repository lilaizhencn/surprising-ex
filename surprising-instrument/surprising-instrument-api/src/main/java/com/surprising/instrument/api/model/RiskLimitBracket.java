package com.surprising.instrument.api.model;

public record RiskLimitBracket(
        int bracketNo,
        long notionalFloorUnits,
        long notionalCapUnits,
        long maxLeveragePpm,
        long initialMarginRatePpm,
        long maintenanceMarginRatePpm,
        long optionMarginFactorPpm) {

    public RiskLimitBracket(
            int bracketNo,
            long notionalFloorUnits,
            long notionalCapUnits,
            long maxLeveragePpm,
            long initialMarginRatePpm,
            long maintenanceMarginRatePpm) {
        this(bracketNo, notionalFloorUnits, notionalCapUnits, maxLeveragePpm,
                initialMarginRatePpm, maintenanceMarginRatePpm, 1_000_000L);
    }
}
