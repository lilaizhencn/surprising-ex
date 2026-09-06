package com.surprising.aeron.protocol;

public record CorePositionView(
        String symbol,
        String marginAsset,
        CoreMarginMode marginMode,
        CorePositionSide positionSide,
        long instrumentChangeId,
        long signedQuantitySteps,
        long entryPriceTicks,
        long entryValueTicks,
        long realizedPnlUnits,
        long positionMarginUnits) {

    public CorePositionView(String symbol, String marginAsset, long instrumentChangeId,
                            long signedQuantitySteps, long entryPriceTicks, long entryValueTicks,
                            long realizedPnlUnits, long positionMarginUnits) {
        this(symbol, marginAsset, CoreMarginMode.CROSS, CorePositionSide.NET, instrumentChangeId,
                signedQuantitySteps, entryPriceTicks, entryValueTicks, realizedPnlUnits, positionMarginUnits);
    }
}
