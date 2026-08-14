package com.surprising.adl.provider.model;

public record CoreAdlLiquidationProjection(
        long liquidationId,
        long userId,
        String symbol,
        String asset,
        long signedQuantitySteps,
        long deficitUnits) {
}
