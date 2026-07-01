package com.surprising.instrument.api.model;

public record RiskLimitBracket(
        int bracketNo,
        long notionalFloorUnits,
        long notionalCapUnits,
        long maxLeveragePpm,
        long initialMarginRatePpm,
        long maintenanceMarginRatePpm) {
}
