package com.surprising.aeron.protocol;

public record CoreLiquidationActionView(
        long liquidationId,
        long userId,
        String symbol,
        CoreMarginMode marginMode,
        CorePositionSide positionSide,
        long instrumentVersion,
        long triggerPriceSequence,
        long signedQuantitySteps,
        long closeQuantitySteps,
        long markPriceTicks) {

    public CoreLiquidationActionView {
        if (liquidationId <= 0 || userId <= 0 || symbol == null || symbol.isBlank()
                || marginMode == null || positionSide == null || instrumentVersion <= 0
                || triggerPriceSequence <= 0 || signedQuantitySteps == 0 || closeQuantitySteps <= 0
                || closeQuantitySteps > Math.absExact(signedQuantitySteps) || markPriceTicks <= 0) {
            throw new IllegalArgumentException("invalid Core liquidation action");
        }
    }
}
