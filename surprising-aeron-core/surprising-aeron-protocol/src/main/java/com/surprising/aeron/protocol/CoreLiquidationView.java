package com.surprising.aeron.protocol;

public record CoreLiquidationView(
        long liquidationId,
        long userId,
        String symbol,
        String asset,
        CorePositionSide positionSide,
        long instrumentVersion,
        long triggerPriceSequence,
        long closeQuantitySteps,
        long deficitUnits,
        String status) {

    public CoreLiquidationView {
        if (liquidationId <= 0 || userId <= 0 || symbol == null || symbol.isBlank()
                || asset == null || asset.isBlank() || positionSide == null || instrumentVersion <= 0
                || triggerPriceSequence <= 0 || closeQuantitySteps <= 0 || deficitUnits < 0
                || status == null || status.isBlank()) {
            throw new IllegalArgumentException("invalid core liquidation view");
        }
    }
}
