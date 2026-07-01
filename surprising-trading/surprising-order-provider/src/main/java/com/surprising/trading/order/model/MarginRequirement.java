package com.surprising.trading.order.model;

public record MarginRequirement(
        String asset,
        long initialMarginUnits,
        String rejectReason,
        long leveragePpm,
        long maxLeveragePpm,
        long initialMarginRatePpm) {

    public MarginRequirement(String asset, long initialMarginUnits) {
        this(asset, initialMarginUnits, null, 0L, 0L, 0L);
    }

    public boolean accepted() {
        return rejectReason == null || rejectReason.isBlank();
    }
}
