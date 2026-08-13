package com.surprising.aeron.protocol;

public record CorePositionView(
        String symbol,
        String marginAsset,
        long instrumentVersion,
        long signedQuantitySteps,
        long entryPriceTicks,
        long entryValueTicks,
        long realizedPnlUnits,
        long positionMarginUnits) {
}
