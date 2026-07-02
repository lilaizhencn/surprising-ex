package com.surprising.risk.api.model;

import java.time.Instant;

public record RiskAccountUpdatedEvent(
        long eventId,
        long snapshotId,
        long userId,
        String settleAsset,
        long walletBalanceUnits,
        long unrealizedPnlUnits,
        long equityUnits,
        long maintenanceMarginUnits,
        long marginRatioPpm,
        RiskStatus status,
        Instant eventTime,
        String traceId) {

    public RiskAccountUpdatedEvent(long eventId,
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
        this(eventId, snapshotId, userId, settleAsset, walletBalanceUnits, unrealizedPnlUnits, equityUnits,
                maintenanceMarginUnits, marginRatioPpm, status, eventTime, null);
    }

    public static RiskAccountUpdatedEvent from(long eventId, RiskAccountSnapshotResponse snapshot) {
        return from(eventId, snapshot, null);
    }

    public static RiskAccountUpdatedEvent from(long eventId, RiskAccountSnapshotResponse snapshot, String traceId) {
        return new RiskAccountUpdatedEvent(eventId, snapshot.snapshotId(), snapshot.userId(), snapshot.settleAsset(),
                snapshot.walletBalanceUnits(), snapshot.unrealizedPnlUnits(), snapshot.equityUnits(),
                snapshot.maintenanceMarginUnits(), snapshot.marginRatioPpm(), snapshot.status(),
                snapshot.eventTime(), traceId);
    }
}
