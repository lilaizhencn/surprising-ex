package com.surprising.aeron.service.state;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

public record CoreRiskState(
        Map<String, CoreMarkPriceState> markPrices,
        Map<String, CoreRiskSnapshot> snapshots,
        Map<Long, CoreLiquidationState> liquidations,
        Map<String, RiskScan> scans,
        long nextLiquidationId) {

    public CoreRiskState {
        if (markPrices == null || snapshots == null || liquidations == null || scans == null
                || nextLiquidationId <= 0) {
            throw new IllegalArgumentException("invalid risk state");
        }
        markPrices = Collections.unmodifiableMap(new TreeMap<>(markPrices));
        snapshots = Collections.unmodifiableMap(new TreeMap<>(snapshots));
        liquidations = Collections.unmodifiableMap(new TreeMap<>(liquidations));
        scans = Collections.unmodifiableMap(new TreeMap<>(scans));
    }

    public static CoreRiskState empty() {
        return new CoreRiskState(Map.of(), Map.of(), Map.of(), Map.of(), 1);
    }

    public CoreRiskState(Map<String, CoreMarkPriceState> markPrices,
                         Map<String, CoreRiskSnapshot> snapshots,
                         Map<Long, CoreLiquidationState> liquidations,
                         RiskScan scan,
                         long nextLiquidationId) {
        this(markPrices, snapshots, liquidations,
                scan == null || "-".equals(scan.symbol()) ? Map.of() : Map.of(scan.symbol(), scan),
                nextLiquidationId);
    }

    public RiskScan scan() {
        return scans.values().stream().filter(value -> !value.complete()).findFirst()
                .orElseGet(() -> scans.values().stream().findFirst().orElseGet(RiskScan::idle));
    }

    public boolean hasPendingScans() {
        return scans.values().stream().anyMatch(value -> !value.complete());
    }

    public record RiskScan(
            String symbol,
            long priceSequence,
            long scanStartPriceSequence,
            long lastUserId,
            boolean complete) {

        public RiskScan {
            if (priceSequence < 0 || scanStartPriceSequence < 0
                    || scanStartPriceSequence > priceSequence || lastUserId < 0) {
                throw new IllegalArgumentException("invalid risk scan");
            }
            symbol = symbol == null || symbol.isBlank() ? "-" : symbol;
        }

        public RiskScan(String symbol, long priceSequence, long lastUserId, boolean complete) {
            this(symbol, priceSequence, priceSequence, lastUserId, complete);
        }

        static RiskScan idle() {
            return new RiskScan("-", 0, 0, 0, true);
        }
    }
}
