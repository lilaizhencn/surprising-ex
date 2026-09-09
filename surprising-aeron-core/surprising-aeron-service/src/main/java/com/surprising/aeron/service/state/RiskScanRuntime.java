package com.surprising.aeron.service.state;

import java.util.List;

import com.surprising.aeron.service.state.model.RiskLaneProgress;

/** 币对风险扫描的 owner 元数据；标量游标是当前调度 Lane 的查询摘要，laneProgress 保存完整进度。 */
public record RiskScanRuntime(int symbolId, int accountLaneId, long priceSequence, long scanStartPriceSequence,
                              long lastUserId, boolean riskComplete, long riskUserId, int riskPhase,
                              String riskPositionCursor, long riskReservationCursor,
                              long riskUnrealizedPnlUnits, long riskMaintenanceMarginUnits,
                              long riskIsolatedMarginUnits, long riskIsolatedReservationUnits,
                              boolean triggerComplete, int triggerPhase, long triggerPriceCursor,
                              long triggerOrderCursor, long triggerUpperId, long triggerMarkPriceTicks,
                              long triggerGeneratedAtEpochMillis, long triggerOcoOrderId, long triggerOcoCursor, long lastScheduledRevision,
            /** 各 Lane 独立恢复的风险进度；空列表表示扫描尚未分派。 */ List<RiskLaneProgress> laneProgress) {
    public RiskScanRuntime(int symbolId, int accountLaneId, long priceSequence, long scanStartPriceSequence,
                              long lastUserId, boolean riskComplete, long riskUserId, int riskPhase,
                              String riskPositionCursor, long riskReservationCursor,
                              long riskUnrealizedPnlUnits, long riskMaintenanceMarginUnits,
                              long riskIsolatedMarginUnits, long riskIsolatedReservationUnits,
                              boolean triggerComplete, int triggerPhase, long triggerPriceCursor,
                              long triggerOrderCursor, long triggerUpperId, long triggerMarkPriceTicks,
                              long triggerGeneratedAtEpochMillis, long triggerOcoOrderId, long triggerOcoCursor, long lastScheduledRevision) {
        this(symbolId, accountLaneId, priceSequence, scanStartPriceSequence, lastUserId, riskComplete,
                riskUserId, riskPhase, riskPositionCursor, riskReservationCursor, riskUnrealizedPnlUnits,
                riskMaintenanceMarginUnits, riskIsolatedMarginUnits, riskIsolatedReservationUnits,
                triggerComplete, triggerPhase, triggerPriceCursor, triggerOrderCursor, triggerUpperId,
                triggerMarkPriceTicks, triggerGeneratedAtEpochMillis, triggerOcoOrderId, triggerOcoCursor,
                lastScheduledRevision, List.of());
    }

    public RiskScanRuntime(int symbolId, int accountLaneId, long priceSequence, long scanStartPriceSequence,
                              long lastUserId, boolean riskComplete, long riskUserId, int riskPhase,
                              String riskPositionCursor, long riskReservationCursor,
                              long riskUnrealizedPnlUnits, long riskMaintenanceMarginUnits,
                              long riskIsolatedMarginUnits, long riskIsolatedReservationUnits,
                              boolean triggerComplete, int triggerPhase, long triggerPriceCursor,
                              long triggerOrderCursor, long triggerUpperId, long triggerMarkPriceTicks,
                              long triggerGeneratedAtEpochMillis, long triggerOcoOrderId, long triggerOcoCursor) {
        this(symbolId, accountLaneId, priceSequence, scanStartPriceSequence, lastUserId, riskComplete, riskUserId,
                riskPhase, riskPositionCursor, riskReservationCursor, riskUnrealizedPnlUnits,
                riskMaintenanceMarginUnits, riskIsolatedMarginUnits, riskIsolatedReservationUnits, triggerComplete,
                triggerPhase, triggerPriceCursor, triggerOrderCursor, triggerUpperId, triggerMarkPriceTicks,
                triggerGeneratedAtEpochMillis, triggerOcoOrderId, triggerOcoCursor, 0);
    }

    public RiskScanRuntime {
        laneProgress = List.copyOf(laneProgress);
        if (laneProgress.size() > Long.SIZE) throw new IllegalArgumentException("too many risk Lanes");
        if (lastScheduledRevision < 0 || symbolId < 0 || accountLaneId < 0 || accountLaneId >= Long.SIZE
                || priceSequence < 0 || scanStartPriceSequence < 0
                || scanStartPriceSequence > priceSequence || lastUserId < 0 || riskUserId < 0
                || riskPhase < 0 || riskPhase > 2 || riskReservationCursor < 0
                || riskMaintenanceMarginUnits < 0 || riskIsolatedMarginUnits < 0
                || riskIsolatedReservationUnits < 0 || triggerPhase < 0 || triggerPriceCursor < 0
                || triggerOrderCursor < 0 || triggerUpperId < 0 || triggerMarkPriceTicks < 0
                || triggerGeneratedAtEpochMillis < 0 || triggerOcoOrderId < 0 || triggerOcoCursor < 0) {
            throw new IllegalArgumentException("invalid runtime risk scan");
        }
        riskPositionCursor = riskPositionCursor == null || riskPositionCursor.isBlank()
                ? "-" : riskPositionCursor;
        if (riskComplete && riskUserId != 0) {
            throw new IllegalArgumentException("complete runtime risk scan cannot retain a user cursor");
        }
    }

    public RiskScanRuntime(int symbolId, long priceSequence, long scanStartPriceSequence,
                           long lastUserId, boolean riskComplete, long riskUserId, int riskPhase,
                           String riskPositionCursor, long riskReservationCursor,
                           long riskUnrealizedPnlUnits, long riskMaintenanceMarginUnits,
                           long riskIsolatedMarginUnits, long riskIsolatedReservationUnits,
                           boolean triggerComplete, int triggerPhase, long triggerPriceCursor,
                           long triggerOrderCursor, long triggerUpperId, long triggerMarkPriceTicks,
                           long triggerGeneratedAtEpochMillis, long triggerOcoOrderId, long triggerOcoCursor) {
        this(symbolId, 0, priceSequence, scanStartPriceSequence, lastUserId, riskComplete, riskUserId,
                riskPhase, riskPositionCursor, riskReservationCursor, riskUnrealizedPnlUnits,
                riskMaintenanceMarginUnits, riskIsolatedMarginUnits, riskIsolatedReservationUnits,
                triggerComplete, triggerPhase, triggerPriceCursor, triggerOrderCursor, triggerUpperId,
                triggerMarkPriceTicks, triggerGeneratedAtEpochMillis, triggerOcoOrderId, triggerOcoCursor);
    }

    public RiskScanRuntime withLastScheduledRevision(long revision) {
        return new RiskScanRuntime(symbolId, accountLaneId, priceSequence, scanStartPriceSequence, lastUserId,
                riskComplete, riskUserId, riskPhase, riskPositionCursor, riskReservationCursor,
                riskUnrealizedPnlUnits, riskMaintenanceMarginUnits, riskIsolatedMarginUnits,
                riskIsolatedReservationUnits, triggerComplete, triggerPhase, triggerPriceCursor, triggerOrderCursor,
                triggerUpperId, triggerMarkPriceTicks, triggerGeneratedAtEpochMillis, triggerOcoOrderId,
                triggerOcoCursor, revision, laneProgress);
    }

    public RiskScanRuntime withLaneProgress(List<RiskLaneProgress> progress) {
        return new RiskScanRuntime(symbolId, accountLaneId, priceSequence, scanStartPriceSequence, lastUserId,
                riskComplete, riskUserId, riskPhase, riskPositionCursor, riskReservationCursor,
                riskUnrealizedPnlUnits, riskMaintenanceMarginUnits, riskIsolatedMarginUnits,
                riskIsolatedReservationUnits, triggerComplete, triggerPhase, triggerPriceCursor,
                triggerOrderCursor, triggerUpperId, triggerMarkPriceTicks, triggerGeneratedAtEpochMillis,
                triggerOcoOrderId, triggerOcoCursor, lastScheduledRevision, progress);
    }

    public boolean complete() {
        return riskComplete && triggerComplete;
    }

    public RiskScanRuntime withRiskProgress(boolean complete, long userId, int phase, String positionCursor,
                                            long reservationCursor, long unrealizedPnlUnits,
                                            long maintenanceMarginUnits, long isolatedMarginUnits,
                                            long isolatedReservationUnits, long completedUserId) {
        return new RiskScanRuntime(symbolId, accountLaneId, priceSequence, scanStartPriceSequence,
                completedUserId, complete,
                userId, phase, positionCursor, reservationCursor, unrealizedPnlUnits, maintenanceMarginUnits,
                isolatedMarginUnits, isolatedReservationUnits, triggerComplete, triggerPhase, triggerPriceCursor,
                triggerOrderCursor, triggerUpperId, triggerMarkPriceTicks, triggerGeneratedAtEpochMillis,
                triggerOcoOrderId, triggerOcoCursor, lastScheduledRevision);
    }

    public RiskScanRuntime withTriggerProgress(boolean complete, int phase, long priceCursor, long orderCursor,
                                               long upperId, long markPriceTicks,
                                               long generatedAtEpochMillis) {
        return new RiskScanRuntime(symbolId, accountLaneId, priceSequence, scanStartPriceSequence,
                lastUserId, riskComplete,
                riskUserId, riskPhase, riskPositionCursor, riskReservationCursor, riskUnrealizedPnlUnits,
                riskMaintenanceMarginUnits, riskIsolatedMarginUnits, riskIsolatedReservationUnits,
                complete, phase, priceCursor, orderCursor, upperId, markPriceTicks, generatedAtEpochMillis,
                triggerOcoOrderId, triggerOcoCursor, lastScheduledRevision, laneProgress);
    }

    public RiskScanRuntime withTriggerOcoProgress(long orderId, long cursor) {
        return new RiskScanRuntime(symbolId, accountLaneId, priceSequence, scanStartPriceSequence,
                lastUserId, riskComplete,
                riskUserId, riskPhase, riskPositionCursor, riskReservationCursor, riskUnrealizedPnlUnits,
                riskMaintenanceMarginUnits, riskIsolatedMarginUnits, riskIsolatedReservationUnits,
                triggerComplete, triggerPhase, triggerPriceCursor, triggerOrderCursor, triggerUpperId,
                triggerMarkPriceTicks, triggerGeneratedAtEpochMillis, orderId, cursor, lastScheduledRevision, laneProgress);
    }

    public RiskScanRuntime nextAccountLane(int laneId) {
        return new RiskScanRuntime(symbolId, laneId, priceSequence, scanStartPriceSequence, 0, false,
                0, 0, "-", 0, 0, 0, 0, 0, triggerComplete, triggerPhase, triggerPriceCursor,
                triggerOrderCursor, triggerUpperId, triggerMarkPriceTicks, triggerGeneratedAtEpochMillis,
                triggerOcoOrderId, triggerOcoCursor, lastScheduledRevision);
    }
}
