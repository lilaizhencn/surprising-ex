package com.surprising.trading.order.model;

public record MarginRequirement(
        String accountType,
        String asset,
        long initialMarginUnits,
        String rejectReason,
        long leveragePpm,
        long maxLeveragePpm,
        long initialMarginRatePpm) {

    public MarginRequirement(String asset, long initialMarginUnits) {
        this("USDT_PERPETUAL", asset, initialMarginUnits, null, 0L, 0L, 0L);
    }

    public MarginRequirement(String accountType, String asset, long initialMarginUnits) {
        this(accountType, asset, initialMarginUnits, null, 0L, 0L, 0L);
    }

    public MarginRequirement(String asset,
                             long initialMarginUnits,
                             String rejectReason,
                             long leveragePpm,
                             long maxLeveragePpm,
                             long initialMarginRatePpm) {
        this("USDT_PERPETUAL", asset, initialMarginUnits, rejectReason, leveragePpm, maxLeveragePpm,
                initialMarginRatePpm);
    }

    public MarginRequirement {
        if (accountType == null || accountType.isBlank()) {
            accountType = "USDT_PERPETUAL";
        }
    }

    public boolean accepted() {
        return rejectReason == null || rejectReason.isBlank();
    }
}
