package com.surprising.instrument.api.model;

import java.math.BigDecimal;

public record RiskLimitBracket(
        int bracketNo,
        BigDecimal notionalFloor,
        BigDecimal notionalCap,
        BigDecimal maxLeverage,
        BigDecimal initialMarginRate,
        BigDecimal maintenanceMarginRate) {
}
