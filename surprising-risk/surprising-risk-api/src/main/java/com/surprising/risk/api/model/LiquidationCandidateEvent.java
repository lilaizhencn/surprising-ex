package com.surprising.risk.api.model;

import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.time.Instant;
import java.util.Objects;

public record LiquidationCandidateEvent(
        long candidateId,
        long snapshotId,
        long userId,
        String symbol,
        MarginMode marginMode,
        PositionSide positionSide,
        long instrumentVersion,
        String settleAsset,
        long signedQuantitySteps,
        long markPriceTicks,
        long equityUnits,
        long maintenanceMarginUnits,
        long marginRatioPpm,
        Instant eventTime,
        long positionRevision) {

    public LiquidationCandidateEvent {
        marginMode = Objects.requireNonNull(marginMode, "marginMode is required");
        positionSide = Objects.requireNonNull(positionSide, "positionSide is required");
        if (candidateId <= 0L || snapshotId <= 0L || userId <= 0L || instrumentVersion <= 0L
                || positionRevision <= 0L || eventTime == null) {
            throw new IllegalArgumentException("invalid liquidation candidate identity or revision");
        }
    }
}
