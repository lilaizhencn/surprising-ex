package com.surprising.risk.provider.model;

public record CalculatedPositionRisk(
        long userId,
        String symbol,
        long instrumentVersion,
        String settleAsset,
        long signedQuantitySteps,
        long entryPriceTicks,
        long markPriceTicks,
        long notionalUnits,
        long unrealizedPnlUnits,
        long maintenanceMarginUnits) {
}
