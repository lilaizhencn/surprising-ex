package com.surprising.insurance.provider.model;

public record CoreLiquidationProjection(
        long liquidationId,
        long userId,
        String asset,
        long deficitUnits) {
}
