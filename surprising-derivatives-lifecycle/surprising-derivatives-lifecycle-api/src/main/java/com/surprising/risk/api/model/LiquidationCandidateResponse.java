package com.surprising.risk.api.model;

import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.time.Instant;

public record LiquidationCandidateResponse(
        long candidateId,
        long snapshotId,
        long userId,
        String instrumentId,
        MarginMode marginMode,
        PositionSide positionSide,
        String accountType,
        String settleAsset,
        long signedQuantitySteps,
        long markPriceTicks,
        long equityUnits,
        long maintenanceMarginUnits,
        long marginRatioPpm,
        LiquidationCandidateStatus status,
        Instant eventTime) {

    public LiquidationCandidateResponse {
        marginMode = MarginMode.defaultIfNull(marginMode);
        positionSide = PositionSide.defaultIfNull(positionSide);
        accountType = normalizeAccountType(accountType);
    }

    private static String normalizeAccountType(String value) {
        return value == null || value.isBlank() ? "USDT_PERPETUAL" : value.trim().toUpperCase();
    }
}
