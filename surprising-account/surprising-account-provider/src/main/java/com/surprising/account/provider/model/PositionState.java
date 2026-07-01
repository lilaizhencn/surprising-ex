package com.surprising.account.provider.model;

public record PositionState(
        long signedQuantitySteps,
        long instrumentVersion,
        long entryPriceTicks,
        long realizedPnlUnits) {
}
