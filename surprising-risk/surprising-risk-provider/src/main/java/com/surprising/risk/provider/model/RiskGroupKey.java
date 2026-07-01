package com.surprising.risk.provider.model;

public record RiskGroupKey(
        long userId,
        String settleAsset) {
}
