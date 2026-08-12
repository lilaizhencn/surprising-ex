package com.surprising.risk.api.model;

import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.time.Instant;

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
        marginMode = MarginMode.defaultIfNull(marginMode);
        positionSide = PositionSide.defaultIfNull(positionSide);
        // 兼容迁移前已经写入的候选事件：旧消息没有持仓版本，使用风险快照版本作为校验版本。
        positionRevision = positionRevision <= 0L ? snapshotId : positionRevision;
        if (candidateId <= 0L || snapshotId <= 0L || userId <= 0L || instrumentVersion <= 0L
                || positionRevision <= 0L || eventTime == null) {
            throw new IllegalArgumentException("invalid liquidation candidate identity or revision");
        }
    }

    /** 兼容持仓版本字段加入前的完整构造方式。 */
    public LiquidationCandidateEvent(long candidateId,
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
                                     Instant eventTime) {
        this(candidateId, snapshotId, userId, symbol, marginMode, positionSide, instrumentVersion, settleAsset,
                signedQuantitySteps, markPriceTicks, equityUnits, maintenanceMarginUnits, marginRatioPpm, eventTime,
                snapshotId);
    }

    public LiquidationCandidateEvent(long candidateId,
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
        this(candidateId, snapshotId, userId, symbol, marginMode, PositionSide.NET, instrumentVersion, settleAsset,
                signedQuantitySteps, markPriceTicks, equityUnits, maintenanceMarginUnits, marginRatioPpm, eventTime,
                snapshotId);
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
        this(candidateId, snapshotId, userId, symbol, MarginMode.CROSS, PositionSide.NET, instrumentVersion, settleAsset,
                signedQuantitySteps, markPriceTicks, equityUnits, maintenanceMarginUnits, marginRatioPpm, eventTime,
                snapshotId);
    }
}
