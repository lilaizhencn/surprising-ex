package com.surprising.aeron.protocol;

public record CoreRiskSnapshotView(long userId, String symbol, CorePositionSide positionSide,
                                   long priceSequence, long equityUnits, long unrealizedPnlUnits,
                                   long maintenanceMarginUnits, long marginRatioPpm, String status) {
}
