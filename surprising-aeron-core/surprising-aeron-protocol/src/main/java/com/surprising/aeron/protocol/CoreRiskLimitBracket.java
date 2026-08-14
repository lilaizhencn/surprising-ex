package com.surprising.aeron.protocol;

public record CoreRiskLimitBracket(
        int bracketNo,
        long notionalFloorUnits,
        long notionalCapUnits,
        long maxLeveragePpm,
        long initialMarginRatePpm,
        long maintenanceMarginRatePpm) {

    public CoreRiskLimitBracket {
        if (bracketNo <= 0 || notionalFloorUnits < 0 || notionalCapUnits <= notionalFloorUnits
                || maxLeveragePpm < 1_000_000L || initialMarginRatePpm <= 0
                || initialMarginRatePpm > 1_000_000L || maintenanceMarginRatePpm <= 0
                || maintenanceMarginRatePpm > 1_000_000L) {
            throw new IllegalArgumentException("invalid risk limit bracket");
        }
    }
}
