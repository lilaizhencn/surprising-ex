package com.surprising.risk.provider.model;

public record PositionRiskTarget(
        long userId,
        String symbol,
        long instrumentVersion,
        String settleAsset) {

    public RiskGroupKey riskGroupKey() {
        return new RiskGroupKey(userId, settleAsset);
    }
}
