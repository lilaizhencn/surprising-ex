package com.surprising.aeron.service.state;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

public record CoreRiskState(
        Map<String, CoreMarkPriceState> markPrices,
        Map<String, CoreRiskSnapshot> snapshots,
        Map<Long, CoreLiquidationState> liquidations,
        RiskScan scan,
        long nextLiquidationId) {

    public CoreRiskState {
        if (markPrices == null || snapshots == null || liquidations == null || scan == null
                || nextLiquidationId <= 0) {
            throw new IllegalArgumentException("invalid risk state");
        }
        markPrices = Collections.unmodifiableMap(new TreeMap<>(markPrices));
        snapshots = Collections.unmodifiableMap(new TreeMap<>(snapshots));
        liquidations = Collections.unmodifiableMap(new TreeMap<>(liquidations));
    }

    public static CoreRiskState empty() {
        return new CoreRiskState(Map.of(), Map.of(), Map.of(), RiskScan.idle(), 1);
    }

    public record RiskScan(String symbol, long priceSequence, long lastUserId, boolean complete) {
        public RiskScan {
            if (priceSequence < 0 || lastUserId < 0) {
                throw new IllegalArgumentException("invalid risk scan");
            }
            symbol = symbol == null || symbol.isBlank() ? "-" : symbol;
        }

        static RiskScan idle() {
            return new RiskScan("-", 0, 0, true);
        }
    }
}
