package com.surprising.risk.provider.model;

public record RiskGroupKey(
        long userId,
        String accountType,
        String settleAsset) {

    public RiskGroupKey {
        accountType = accountType == null || accountType.isBlank()
                ? "USDT_PERPETUAL"
                : accountType.trim().toUpperCase();
    }

    public RiskGroupKey(long userId, String settleAsset) {
        this(userId, "USDT_PERPETUAL", settleAsset);
    }
}
