package com.surprising.aeron.service.state;

public record RiskScanRuntime(int symbolId, int accountLaneId, long priceSequence, long scanStartPriceSequence,
                              long lastUserId, boolean riskComplete, long riskUserId, int riskPhase,
                              String riskPositionCursor, long riskReservationCursor,
                              long riskUnrealizedPnlUnits, long riskMaintenanceMarginUnits,
                              long riskIsolatedMarginUnits, long riskIsolatedReservationUnits,
                              boolean triggerComplete, int triggerPhase, long triggerPriceCursor,
                              long triggerOrderCursor, long triggerUpperId, long triggerMarkPriceTicks,
                              long triggerGeneratedAtEpochMillis, long triggerOcoOrderId, long triggerOcoCursor) {
    public RiskScanRuntime {
        if (symbolId < 0 || accountLaneId < 0 || accountLaneId >= Long.SIZE
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
                triggerOcoOrderId, triggerOcoCursor);
    }

    public RiskScanRuntime withTriggerProgress(boolean complete, int phase, long priceCursor, long orderCursor,
                                               long upperId, long markPriceTicks,
                                               long generatedAtEpochMillis) {
        return new RiskScanRuntime(symbolId, accountLaneId, priceSequence, scanStartPriceSequence,
                lastUserId, riskComplete,
                riskUserId, riskPhase, riskPositionCursor, riskReservationCursor, riskUnrealizedPnlUnits,
                riskMaintenanceMarginUnits, riskIsolatedMarginUnits, riskIsolatedReservationUnits,
                complete, phase, priceCursor, orderCursor, upperId, markPriceTicks, generatedAtEpochMillis,
                triggerOcoOrderId, triggerOcoCursor);
    }

    public RiskScanRuntime withTriggerOcoProgress(long orderId, long cursor) {
        return new RiskScanRuntime(symbolId, accountLaneId, priceSequence, scanStartPriceSequence,
                lastUserId, riskComplete,
                riskUserId, riskPhase, riskPositionCursor, riskReservationCursor, riskUnrealizedPnlUnits,
                riskMaintenanceMarginUnits, riskIsolatedMarginUnits, riskIsolatedReservationUnits,
                triggerComplete, triggerPhase, triggerPriceCursor, triggerOrderCursor, triggerUpperId,
                triggerMarkPriceTicks, triggerGeneratedAtEpochMillis, orderId, cursor);
    }

    public RiskScanRuntime nextAccountLane(int laneId) {
        return new RiskScanRuntime(symbolId, laneId, priceSequence, scanStartPriceSequence, 0, false,
                0, 0, "-", 0, 0, 0, 0, 0, triggerComplete, triggerPhase, triggerPriceCursor,
                triggerOrderCursor, triggerUpperId, triggerMarkPriceTicks, triggerGeneratedAtEpochMillis,
                triggerOcoOrderId, triggerOcoCursor);
    }
}
