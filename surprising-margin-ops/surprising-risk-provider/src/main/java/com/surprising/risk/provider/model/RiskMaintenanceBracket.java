package com.surprising.risk.provider.model;

public record RiskMaintenanceBracket(
        long notionalFloorUnits,
        long maintenanceMarginRatePpm) {

    public RiskMaintenanceBracket {
        if (notionalFloorUnits < 0L || maintenanceMarginRatePpm <= 0L) {
            throw new IllegalArgumentException("invalid maintenance bracket");
        }
    }
}
