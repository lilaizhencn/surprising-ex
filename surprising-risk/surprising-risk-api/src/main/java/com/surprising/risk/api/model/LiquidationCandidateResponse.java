package com.surprising.risk.api.model;

import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.time.Instant;

public record LiquidationCandidateResponse(
        long candidateId,
        long snapshotId,
        long userId,
        String symbol,
        MarginMode marginMode,
        PositionSide positionSide,
        long instrumentVersion,
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

    public LiquidationCandidateResponse(long candidateId,
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
                                        LiquidationCandidateStatus status,
                                        Instant eventTime) {
        this(candidateId, snapshotId, userId, symbol, marginMode, positionSide, instrumentVersion,
                "USDT_PERPETUAL", settleAsset, signedQuantitySteps, markPriceTicks, equityUnits,
                maintenanceMarginUnits, marginRatioPpm, status, eventTime);
    }

    public LiquidationCandidateResponse(long candidateId,
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
                                        LiquidationCandidateStatus status,
                                        Instant eventTime) {
        this(candidateId, snapshotId, userId, symbol, marginMode, PositionSide.NET, instrumentVersion,
                "USDT_PERPETUAL", settleAsset,
                signedQuantitySteps, markPriceTicks, equityUnits, maintenanceMarginUnits, marginRatioPpm, status,
                eventTime);
    }

    public LiquidationCandidateResponse(long candidateId,
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
                                        LiquidationCandidateStatus status,
                                        Instant eventTime) {
        this(candidateId, snapshotId, userId, symbol, MarginMode.CROSS, PositionSide.NET, instrumentVersion,
                "USDT_PERPETUAL", settleAsset,
                signedQuantitySteps, markPriceTicks, equityUnits, maintenanceMarginUnits, marginRatioPpm, status,
                eventTime);
    }

    private static String normalizeAccountType(String value) {
        return value == null || value.isBlank() ? "USDT_PERPETUAL" : value.trim().toUpperCase();
    }
}
