package com.surprising.aeron.service.state;

public record RiskScanRuntime(int symbolId, long priceSequence, long scanStartPriceSequence,
                              long lastUserId, boolean riskComplete, long riskUserId, int riskPhase,
                              String riskPositionCursor, long riskReservationCursor,
                              long riskUnrealizedPnlUnits, long riskMaintenanceMarginUnits,
                              long riskIsolatedMarginUnits, long riskIsolatedReservationUnits,
                              boolean triggerComplete, int triggerPhase, long triggerPriceCursor,
                              long triggerOrderCursor, long triggerUpperId, long triggerMarkPriceTicks,
                              long triggerGeneratedAtEpochMillis, long triggerOcoOrderId, long triggerOcoCursor) {
    public RiskScanRuntime {
        if (symbolId < 0 || priceSequence < 0 || scanStartPriceSequence < 0
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

    public boolean complete() {
        return riskComplete && triggerComplete;
    }

    public RiskScanRuntime withRiskProgress(boolean complete, long userId, int phase, String positionCursor,
                                            long reservationCursor, long unrealizedPnlUnits,
                                            long maintenanceMarginUnits, long isolatedMarginUnits,
                                            long isolatedReservationUnits, long completedUserId) {
        return new RiskScanRuntime(symbolId, priceSequence, scanStartPriceSequence, completedUserId, complete,
                userId, phase, positionCursor, reservationCursor, unrealizedPnlUnits, maintenanceMarginUnits,
                isolatedMarginUnits, isolatedReservationUnits, triggerComplete, triggerPhase, triggerPriceCursor,
                triggerOrderCursor, triggerUpperId, triggerMarkPriceTicks, triggerGeneratedAtEpochMillis,
                triggerOcoOrderId, triggerOcoCursor);
    }
}
