package com.surprising.risk.api.model;

import java.time.Instant;

public record RiskAccountSnapshotResponse(
        long snapshotId,
        long userId,
        String accountType,
        String settleAsset,
        long walletBalanceUnits,
        long unrealizedPnlUnits,
        long equityUnits,
        long maintenanceMarginUnits,
        long marginRatioPpm,
        RiskStatus status,
        Instant eventTime) {

    public RiskAccountSnapshotResponse {
        if (accountType == null || accountType.isBlank()) {
            throw new IllegalArgumentException("accountType is required");
        }
        accountType = accountType.trim().toUpperCase();
    }
}
