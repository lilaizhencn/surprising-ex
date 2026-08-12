package com.surprising.adl.provider.model;

public record DeficitRow(
        String accountType,
        long userId,
        String asset,
        long deficitUnits) {

    public DeficitRow {
        accountType = accountType == null || accountType.isBlank()
                ? "USDT_PERPETUAL"
                : accountType.trim().toUpperCase();
    }

    public DeficitRow(long userId, String asset, long deficitUnits) {
        this("USDT_PERPETUAL", userId, asset, deficitUnits);
    }
}
