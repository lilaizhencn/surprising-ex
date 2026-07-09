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
        accountType = normalizeAccountType(accountType);
    }

    public RiskAccountSnapshotResponse(long snapshotId,
                                       long userId,
                                       String settleAsset,
                                       long walletBalanceUnits,
                                       long unrealizedPnlUnits,
                                       long equityUnits,
                                       long maintenanceMarginUnits,
                                       long marginRatioPpm,
                                       RiskStatus status,
                                       Instant eventTime) {
        this(snapshotId, userId, "USDT_PERPETUAL", settleAsset, walletBalanceUnits, unrealizedPnlUnits,
                equityUnits, maintenanceMarginUnits, marginRatioPpm, status, eventTime);
    }

    private static String normalizeAccountType(String value) {
        return value == null || value.isBlank() ? "USDT_PERPETUAL" : value.trim().toUpperCase();
    }
}
