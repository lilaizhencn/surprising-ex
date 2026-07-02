package com.surprising.risk.api.model;

import com.surprising.trading.api.model.MarginMode;
import java.time.Instant;

public record RiskPositionUpdatedEvent(
        long eventId,
        long snapshotId,
        long userId,
        String symbol,
        MarginMode marginMode,
        long instrumentVersion,
        String settleAsset,
        long signedQuantitySteps,
        long entryPriceTicks,
        long markPriceTicks,
        long notionalUnits,
        long unrealizedPnlUnits,
        long maintenanceMarginUnits,
        long positionMarginUnits,
        long marginRatioPpm,
        RiskStatus status,
        Instant eventTime,
        String traceId) {

    public RiskPositionUpdatedEvent {
        marginMode = MarginMode.defaultIfNull(marginMode);
    }

    public RiskPositionUpdatedEvent(long eventId,
                                    long snapshotId,
                                    long userId,
                                    String symbol,
                                    MarginMode marginMode,
                                    long instrumentVersion,
                                    String settleAsset,
                                    long signedQuantitySteps,
                                    long entryPriceTicks,
                                    long markPriceTicks,
                                    long notionalUnits,
                                    long unrealizedPnlUnits,
                                    long maintenanceMarginUnits,
                                    long positionMarginUnits,
                                    long marginRatioPpm,
                                    RiskStatus status,
                                    Instant eventTime) {
        this(eventId, snapshotId, userId, symbol, marginMode, instrumentVersion, settleAsset, signedQuantitySteps,
                entryPriceTicks, markPriceTicks, notionalUnits, unrealizedPnlUnits, maintenanceMarginUnits,
                positionMarginUnits, marginRatioPpm, status, eventTime, null);
    }
}
