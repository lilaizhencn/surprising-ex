package com.surprising.liquidation.provider.model;

import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;

public record ClaimedCandidate(
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
        long positionRevision) {

    private static final String DEFAULT_ACCOUNT_TYPE = "USDT_PERPETUAL";

    public ClaimedCandidate {
        marginMode = MarginMode.defaultIfNull(marginMode);
        positionSide = PositionSide.defaultIfNull(positionSide);
        accountType = accountType == null || accountType.isBlank()
                ? DEFAULT_ACCOUNT_TYPE
                : accountType.trim().toUpperCase();
    }

    /** 兼容持仓版本字段加入前的完整构造方式。 */
    public ClaimedCandidate(long candidateId,
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
                            long marginRatioPpm) {
        this(candidateId, snapshotId, userId, symbol, marginMode, positionSide, instrumentVersion, accountType,
                settleAsset, signedQuantitySteps, markPriceTicks, equityUnits, maintenanceMarginUnits, marginRatioPpm,
                snapshotId);
    }

    public ClaimedCandidate(long candidateId,
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
                            long marginRatioPpm) {
        this(candidateId, snapshotId, userId, symbol, marginMode, positionSide, instrumentVersion,
                DEFAULT_ACCOUNT_TYPE, settleAsset,
                signedQuantitySteps, markPriceTicks, equityUnits, maintenanceMarginUnits, marginRatioPpm, snapshotId);
    }

    public ClaimedCandidate(long candidateId,
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
                            long marginRatioPpm) {
        this(candidateId, snapshotId, userId, symbol, marginMode, PositionSide.NET, instrumentVersion,
                DEFAULT_ACCOUNT_TYPE, settleAsset,
                signedQuantitySteps, markPriceTicks, equityUnits, maintenanceMarginUnits, marginRatioPpm, snapshotId);
    }

    public ClaimedCandidate(long candidateId,
                            long snapshotId,
                            long userId,
                            String symbol,
                            long instrumentVersion,
                            String settleAsset,
                            long signedQuantitySteps,
                            long markPriceTicks,
                            long marginRatioPpm) {
        this(candidateId, snapshotId, userId, symbol, MarginMode.CROSS, PositionSide.NET, instrumentVersion,
                DEFAULT_ACCOUNT_TYPE, settleAsset,
                signedQuantitySteps, markPriceTicks, 0L, 0L, marginRatioPpm, snapshotId);
    }

    public ClaimedCandidate(long candidateId,
                            long snapshotId,
                            long userId,
                            String symbol,
                            long instrumentVersion,
                            String settleAsset,
                            long signedQuantitySteps,
                            long markPriceTicks,
                            long equityUnits,
                            long maintenanceMarginUnits,
                            long marginRatioPpm) {
        this(candidateId, snapshotId, userId, symbol, MarginMode.CROSS, PositionSide.NET, instrumentVersion,
                DEFAULT_ACCOUNT_TYPE, settleAsset,
                signedQuantitySteps, markPriceTicks, equityUnits, maintenanceMarginUnits, marginRatioPpm, snapshotId);
    }
}
