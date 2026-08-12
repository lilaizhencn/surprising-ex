package com.surprising.risk.provider.model;

import java.time.Instant;
import java.util.List;

/** 实时风险发现唯一路径使用的完整 Redis 投影。 */
public record CachedRiskGroup(
        RiskGroupKey key,
        long walletBalanceUnits,
        long walletRevision,
        List<CachedRiskPosition> positions,
        Instant capturedAt) {

    /** 兼容启动恢复和旧测试数据；旧状态没有账户钱包修订号时只能作为基础快照。 */
    public CachedRiskGroup(RiskGroupKey key,
                           long walletBalanceUnits,
                           List<CachedRiskPosition> positions,
                           Instant capturedAt) {
        this(key, walletBalanceUnits, 0L, positions, capturedAt);
    }

    public CachedRiskGroup {
        if (key == null || capturedAt == null || walletRevision < 0) {
            throw new IllegalArgumentException("cached risk group key and capturedAt are required");
        }
        positions = positions == null ? List.of() : List.copyOf(positions);
    }

    public CachedRiskGroup withWallet(long walletBalanceUnits, long walletRevision, Instant capturedAt) {
        return new CachedRiskGroup(key, walletBalanceUnits, walletRevision, positions, capturedAt);
    }
}
