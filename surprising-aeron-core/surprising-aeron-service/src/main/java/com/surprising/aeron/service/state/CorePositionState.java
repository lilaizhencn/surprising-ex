package com.surprising.aeron.service.state;

public record CorePositionState(
        String symbol,
        String marginAsset,
        long instrumentVersion,
        long signedQuantitySteps,
        long entryPriceTicks,
        long entryValueTicks,
        long realizedPnlUnits,
        long positionMarginUnits) {

    public CorePositionState {
        symbol = OrderReservation.normalizeSymbol(symbol);
        marginAsset = AssetBalance.normalizeAsset(marginAsset);
        if (positionMarginUnits < 0) {
            throw new IllegalArgumentException("position margin must not be negative");
        }
        if (signedQuantitySteps == 0) {
            if (instrumentVersion != 0 || entryPriceTicks != 0 || entryValueTicks != 0 || positionMarginUnits != 0) {
                throw new IllegalArgumentException("flat position contains open-position state");
            }
        } else if (instrumentVersion <= 0 || entryPriceTicks <= 0 || entryValueTicks <= 0) {
            throw new IllegalArgumentException("open position is incomplete");
        }
    }
}
