package com.surprising.risk.api.model;

import java.time.Instant;

public record RiskPositionSnapshotResponse(
        long snapshotId,
        long userId,
        String symbol,
        long instrumentVersion,
        String settleAsset,
        long signedQuantitySteps,
        long entryPriceTicks,
        long markPriceTicks,
        long notionalUnits,
        long unrealizedPnlUnits,
        long maintenanceMarginUnits,
        long marginRatioPpm,
        RiskStatus status,
        Instant eventTime) {
}
