package com.surprising.risk.provider.model;

import java.time.Instant;
import java.util.List;

/** Complete Redis projection used by the only live risk-discovery path. */
public record CachedRiskGroup(
        RiskGroupKey key,
        long walletBalanceUnits,
        List<CachedRiskPosition> positions,
        Instant capturedAt) {

    public CachedRiskGroup {
        if (key == null || capturedAt == null) {
            throw new IllegalArgumentException("cached risk group key and capturedAt are required");
        }
        positions = positions == null ? List.of() : List.copyOf(positions);
    }
}
