package com.surprising.aeron.protocol;

public record CoreLiquidationView(
        long liquidationId,
        long userId,
        String symbol,
        String asset,
        CoreMarginMode marginMode,
        CorePositionSide positionSide,
        long instrumentVersion,
        long triggerPriceSequence,
        long signedQuantitySteps,
        long closeQuantitySteps,
        long deficitUnits,
        long executionPriceTicks,
        long liquidationFeeRatePpm,
        long liquidationFeeUnits,
        String status) {

    public CoreLiquidationView {
        if (liquidationId <= 0 || userId <= 0 || symbol == null || symbol.isBlank()
                || asset == null || asset.isBlank() || marginMode == null || positionSide == null
                || instrumentVersion <= 0
                || triggerPriceSequence <= 0 || signedQuantitySteps == 0 || closeQuantitySteps <= 0
                || closeQuantitySteps > Math.absExact(signedQuantitySteps) || deficitUnits < 0
                || executionPriceTicks < 0 || liquidationFeeRatePpm < 0
                || liquidationFeeRatePpm > 1_000_000 || liquidationFeeUnits < 0
                || status == null || status.isBlank()) {
            throw new IllegalArgumentException("invalid core liquidation view");
        }
    }

    public CoreLiquidationView(long liquidationId, long userId, String symbol, String asset,
                               CorePositionSide positionSide, long instrumentVersion,
                               long triggerPriceSequence, long signedQuantitySteps,
                               long closeQuantitySteps, long deficitUnits, String status) {
        this(liquidationId, userId, symbol, asset, CoreMarginMode.CROSS, positionSide, instrumentVersion,
                triggerPriceSequence, signedQuantitySteps, closeQuantitySteps, deficitUnits, 0, 0, 0, status);
    }
}
