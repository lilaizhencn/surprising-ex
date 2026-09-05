package com.surprising.aeron.protocol;

public record CoreRiskSnapshotView(long userId, String symbol, CoreMarginMode marginMode,
                                   CorePositionSide positionSide, long instrumentVersion, String settleAsset,
                                   long signedQuantitySteps, long entryPriceTicks, long markPriceTicks,
                                   long notionalUnits, long positionMarginUnits, long priceSequence,
                                   long walletBalanceUnits, long equityUnits, long unrealizedPnlUnits, long maintenanceMarginUnits,
                                   long marginRatioPpm, String status) {
}
