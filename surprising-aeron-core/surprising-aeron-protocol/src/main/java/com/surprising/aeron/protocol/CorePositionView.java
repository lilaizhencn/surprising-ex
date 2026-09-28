package com.surprising.aeron.protocol;

public record CorePositionView(
        String instrumentId,
        String marginAsset,
        CoreMarginMode marginMode,
        CorePositionSide positionSide,
        long signedQuantitySteps,
        long entryPriceTicks,
        long entryValueTicks,
        long realizedPnlUnits,
        long positionMarginUnits) {

    public CorePositionView(String instrumentId, String marginAsset,
                            long signedQuantitySteps, long entryPriceTicks, long entryValueTicks,
                            long realizedPnlUnits, long positionMarginUnits) {
        this(instrumentId, marginAsset, CoreMarginMode.CROSS, CorePositionSide.NET,
                signedQuantitySteps, entryPriceTicks, entryValueTicks, realizedPnlUnits, positionMarginUnits);
    }
}
