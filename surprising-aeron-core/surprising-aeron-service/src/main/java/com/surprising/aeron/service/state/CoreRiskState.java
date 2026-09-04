package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CoreRiskScanControlView;
import java.util.Map;

public record CoreRiskState(
        Map<String, CoreMarkPriceState> markPrices,
        Map<String, CoreRiskSnapshot> snapshots,
        Map<Long, CoreLiquidationState> liquidations,
        Map<String, RiskScan> scans,
        long nextLiquidationId,
        CoreRiskScanControlView scanControl) {

    public CoreRiskState {
        if (markPrices == null || snapshots == null || liquidations == null || scans == null
                || nextLiquidationId <= 0 || scanControl == null) {
            throw new IllegalArgumentException("invalid risk state");
        }
        markPrices = immutableSorted(markPrices);
        snapshots = immutableSorted(snapshots);
        liquidations = immutableSorted(liquidations);
        scans = immutableSorted(scans);
    }

    public static CoreRiskState empty() {
        return new CoreRiskState(Map.of(), Map.of(), Map.of(), Map.of(), 1, defaultScanControl());
    }

    public CoreRiskState(Map<String, CoreMarkPriceState> markPrices,
                         Map<String, CoreRiskSnapshot> snapshots,
                         Map<Long, CoreLiquidationState> liquidations,
                         Map<String, RiskScan> scans,
                         long nextLiquidationId) {
        this(markPrices, snapshots, liquidations, scans, nextLiquidationId, defaultScanControl());
    }

    public CoreRiskState(Map<String, CoreMarkPriceState> markPrices,
                         Map<String, CoreRiskSnapshot> snapshots,
                         Map<Long, CoreLiquidationState> liquidations,
                         RiskScan scan,
                         long nextLiquidationId) {
        this(markPrices, snapshots, liquidations,
                scan == null || "-".equals(scan.symbol()) ? Map.of() : Map.of(scan.symbol(), scan),
                nextLiquidationId, defaultScanControl());
    }

    public static CoreRiskScanControlView defaultScanControl() {
        return new CoreRiskScanControlView(1, "Aeron risk scan control", true, 1_000L, 64,
                "system", "default", 0);
    }

    public RiskScan scan() {
        return scans.values().stream().filter(value -> !value.complete()).findFirst()
                .orElseGet(() -> scans.values().stream().findFirst().orElseGet(RiskScan::idle));
    }

    public boolean hasPendingScans() {
        return scans.values().stream().anyMatch(value -> !value.complete());
    }

    @SuppressWarnings("unchecked")
    private static <K, V> Map<K, V> immutableSorted(Map<K, V> values) {
        return StateMapSupport.freezeSorted(values);
    }

    public record RiskScan(
            String symbol,
            int accountLaneId,
            long priceSequence,
            long scanStartPriceSequence,
            long lastUserId,
            boolean riskComplete,
            long riskUserId,
            int riskPhase,
            String riskPositionCursor,
            long riskReservationCursor,
            long riskUnrealizedPnlUnits,
            long riskMaintenanceMarginUnits,
            long riskIsolatedMarginUnits,
            long riskIsolatedReservationUnits,
            boolean triggerComplete,
            int triggerPhase,
            long triggerPriceCursor,
            long triggerOrderCursor,
            long triggerUpperId,
            long triggerMarkPriceTicks,
            long triggerGeneratedAtEpochMillis,
            long triggerOcoOrderId,
            long triggerOcoCursor) {

        public RiskScan {
            if (accountLaneId < 0 || accountLaneId >= Long.SIZE || priceSequence < 0 || scanStartPriceSequence < 0
                    || scanStartPriceSequence > priceSequence || lastUserId < 0 || riskUserId < 0
                    || riskPhase < 0 || riskPhase > 2 || riskReservationCursor < 0
                    || riskMaintenanceMarginUnits < 0 || riskIsolatedMarginUnits < 0
                    || riskIsolatedReservationUnits < 0 || triggerPhase < 0
                    || triggerPriceCursor < 0 || triggerOrderCursor < 0 || triggerUpperId < 0
                    || triggerMarkPriceTicks < 0 || triggerGeneratedAtEpochMillis < 0
                    || triggerOcoOrderId < 0 || triggerOcoCursor < 0) {
                throw new IllegalArgumentException("invalid risk scan");
            }
            symbol = symbol == null || symbol.isBlank() ? "-" : symbol;
            riskPositionCursor = riskPositionCursor == null || riskPositionCursor.isBlank()
                    ? "-" : riskPositionCursor;
            if (riskComplete && riskUserId != 0) {
                throw new IllegalArgumentException("complete risk scan cannot retain a user cursor");
            }
        }

        public RiskScan(String symbol, long priceSequence, long scanStartPriceSequence, long lastUserId,
                        boolean riskComplete, long riskUserId, int riskPhase, String riskPositionCursor,
                        long riskReservationCursor, long riskUnrealizedPnlUnits,
                        long riskMaintenanceMarginUnits, long riskIsolatedMarginUnits,
                        long riskIsolatedReservationUnits, boolean triggerComplete, int triggerPhase,
                        long triggerPriceCursor, long triggerOrderCursor, long triggerUpperId,
                        long triggerMarkPriceTicks, long triggerGeneratedAtEpochMillis,
                        long triggerOcoOrderId, long triggerOcoCursor) {
            this(symbol, 0, priceSequence, scanStartPriceSequence, lastUserId, riskComplete, riskUserId,
                    riskPhase, riskPositionCursor, riskReservationCursor, riskUnrealizedPnlUnits,
                    riskMaintenanceMarginUnits, riskIsolatedMarginUnits, riskIsolatedReservationUnits,
                    triggerComplete, triggerPhase, triggerPriceCursor, triggerOrderCursor, triggerUpperId,
                    triggerMarkPriceTicks, triggerGeneratedAtEpochMillis, triggerOcoOrderId, triggerOcoCursor);
        }

        public RiskScan(String symbol, long priceSequence, long scanStartPriceSequence,
                        long lastUserId, boolean complete) {
            this(symbol, 0, priceSequence, scanStartPriceSequence, lastUserId, complete,
                    0, 0, "-", 0, 0, 0, 0, 0,
                    true, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        public RiskScan(String symbol, long priceSequence, long lastUserId, boolean complete) {
            this(symbol, priceSequence, priceSequence, lastUserId, complete);
        }

        public boolean complete() {
            return riskComplete && triggerComplete;
        }

        public RiskScan withRiskProgress(boolean complete, long userId, int phase, String positionCursor,
                                         long reservationCursor, long unrealizedPnlUnits,
                                         long maintenanceMarginUnits, long isolatedMarginUnits,
                                         long isolatedReservationUnits, long completedUserId) {
            return new RiskScan(symbol, accountLaneId, priceSequence, scanStartPriceSequence, completedUserId, complete,
                    userId, phase, positionCursor, reservationCursor, unrealizedPnlUnits,
                    maintenanceMarginUnits, isolatedMarginUnits, isolatedReservationUnits,
                    triggerComplete, triggerPhase, triggerPriceCursor, triggerOrderCursor, triggerUpperId,
                    triggerMarkPriceTicks, triggerGeneratedAtEpochMillis, triggerOcoOrderId, triggerOcoCursor);
        }

        public RiskScan withTriggerProgress(boolean complete, int phase, long priceCursor, long orderCursor,
                                            long upperId, long markPriceTicks, long generatedAtEpochMillis) {
            return new RiskScan(symbol, accountLaneId, priceSequence, scanStartPriceSequence, lastUserId, riskComplete,
                    riskUserId, riskPhase, riskPositionCursor, riskReservationCursor,
                    riskUnrealizedPnlUnits, riskMaintenanceMarginUnits, riskIsolatedMarginUnits,
                    riskIsolatedReservationUnits, complete, phase, priceCursor, orderCursor, upperId,
                    markPriceTicks, generatedAtEpochMillis, triggerOcoOrderId, triggerOcoCursor);
        }

        public RiskScan withTriggerOcoProgress(long orderId, long cursor) {
            return new RiskScan(symbol, accountLaneId, priceSequence, scanStartPriceSequence, lastUserId, riskComplete,
                    riskUserId, riskPhase, riskPositionCursor, riskReservationCursor,
                    riskUnrealizedPnlUnits, riskMaintenanceMarginUnits, riskIsolatedMarginUnits,
                    riskIsolatedReservationUnits, triggerComplete, triggerPhase, triggerPriceCursor,
                    triggerOrderCursor, triggerUpperId, triggerMarkPriceTicks, triggerGeneratedAtEpochMillis,
                    orderId, cursor);
        }

        public RiskScan nextAccountLane(int laneId) {
            return new RiskScan(symbol, laneId, priceSequence, scanStartPriceSequence, 0, false,
                    0, 0, "-", 0, 0, 0, 0, 0,
                    triggerComplete, triggerPhase, triggerPriceCursor, triggerOrderCursor, triggerUpperId,
                    triggerMarkPriceTicks, triggerGeneratedAtEpochMillis, triggerOcoOrderId, triggerOcoCursor);
        }

        static RiskScan idle() {
            return new RiskScan("-", 0, 0, 0, true);
        }
    }
}
