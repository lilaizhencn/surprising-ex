package com.surprising.risk.api.model;

import java.time.Instant;

public record RiskAccountSnapshotResponse(
        long snapshotId,
        long userId,
        String settleAsset,
        long walletBalanceUnits,
        long unrealizedPnlUnits,
        long equityUnits,
        long maintenanceMarginUnits,
        long marginRatioPpm,
        RiskStatus status,
        Instant eventTime) {
}
