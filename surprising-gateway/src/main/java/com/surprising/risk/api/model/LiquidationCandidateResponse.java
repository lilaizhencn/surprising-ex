package com.surprising.risk.api.model;

import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.time.Instant;

/** 已提交候选事实；snapshotId及四项风险指标来自当前Core快照，缺失时为null，不代表候选发生时的风险值。 */
public record LiquidationCandidateResponse(
        long candidateId,
        Long snapshotId,
        long userId,
        String instrumentId,
        MarginMode marginMode,
        PositionSide positionSide,
        String accountType,
        String settleAsset,
        long signedQuantitySteps,
        Long markPriceTicks,
        Long equityUnits,
        Long maintenanceMarginUnits,
        Long marginRatioPpm,
        LiquidationCandidateStatus status,
        Instant eventTime) {}
