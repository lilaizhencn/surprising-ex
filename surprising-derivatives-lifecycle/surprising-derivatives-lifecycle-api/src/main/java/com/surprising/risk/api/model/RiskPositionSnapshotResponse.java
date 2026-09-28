package com.surprising.risk.api.model;

import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.time.Instant;

public record RiskPositionSnapshotResponse(
        long snapshotId,
        long userId,
        String instrumentId,
        MarginMode marginMode,
        PositionSide positionSide,
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

    public RiskPositionSnapshotResponse {
        marginMode = MarginMode.defaultIfNull(marginMode);
        positionSide = PositionSide.defaultIfNull(positionSide);
    }

    public RiskPositionSnapshotResponse(long snapshotId,
                                        long userId,
                                        String instrumentId,
                                        MarginMode marginMode,
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
        this(snapshotId, userId, instrumentId, marginMode, PositionSide.NET, settleAsset,
                signedQuantitySteps, entryPriceTicks, markPriceTicks, notionalUnits, unrealizedPnlUnits,
                maintenanceMarginUnits, positionMarginUnits, marginRatioPpm, status, eventTime);
    }

    public RiskPositionSnapshotResponse(long snapshotId,
                                        long userId,
                                        String instrumentId,
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
        this(snapshotId, userId, instrumentId, MarginMode.CROSS, PositionSide.NET, settleAsset, signedQuantitySteps,
                entryPriceTicks, markPriceTicks, notionalUnits, unrealizedPnlUnits, maintenanceMarginUnits, 0L,
                marginRatioPpm, status, eventTime);
    }
}
