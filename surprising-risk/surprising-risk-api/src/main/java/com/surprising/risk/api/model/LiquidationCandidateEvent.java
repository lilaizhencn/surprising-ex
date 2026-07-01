package com.surprising.risk.api.model;

import com.surprising.trading.api.model.MarginMode;
import java.time.Instant;

public record LiquidationCandidateEvent(
        long candidateId,
        long snapshotId,
        long userId,
        String symbol,
        MarginMode marginMode,
        long instrumentVersion,
        String settleAsset,
        long signedQuantitySteps,
        long markPriceTicks,
        long equityUnits,
        long maintenanceMarginUnits,
        long marginRatioPpm,
        Instant eventTime) {

    public LiquidationCandidateEvent {
        marginMode = MarginMode.defaultIfNull(marginMode);
    }

    public LiquidationCandidateEvent(long candidateId,
                                     long snapshotId,
                                     long userId,
                                     String symbol,
                                     long instrumentVersion,
                                     String settleAsset,
                                     long signedQuantitySteps,
                                     long markPriceTicks,
                                     long equityUnits,
                                     long maintenanceMarginUnits,
                                     long marginRatioPpm,
                                     Instant eventTime) {
        this(candidateId, snapshotId, userId, symbol, MarginMode.CROSS, instrumentVersion, settleAsset,
                signedQuantitySteps, markPriceTicks, equityUnits, maintenanceMarginUnits, marginRatioPpm, eventTime);
    }
}
