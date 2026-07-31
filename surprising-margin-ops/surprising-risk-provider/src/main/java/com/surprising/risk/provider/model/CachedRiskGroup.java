package com.surprising.risk.provider.model;

import java.time.Instant;
import java.util.List;

/** 实时风险发现唯一路径使用的完整 Redis 投影。 */
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
