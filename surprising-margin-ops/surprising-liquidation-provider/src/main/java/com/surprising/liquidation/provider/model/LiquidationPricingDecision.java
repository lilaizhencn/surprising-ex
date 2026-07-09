package com.surprising.liquidation.provider.model;

public record LiquidationPricingDecision(
        long bankruptcyPriceTicks,
        long takeoverPriceTicks,
        long liquidationFeeRatePpm,
        long liquidationFeeUnits) {

    public static LiquidationPricingDecision empty() {
        return new LiquidationPricingDecision(0L, 0L, 0L, 0L);
    }
}
