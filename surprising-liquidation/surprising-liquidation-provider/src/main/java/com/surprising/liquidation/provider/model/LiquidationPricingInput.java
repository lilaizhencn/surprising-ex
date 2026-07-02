package com.surprising.liquidation.provider.model;

import com.surprising.instrument.api.model.ContractType;

public record LiquidationPricingInput(
        ContractType contractType,
        long signedQuantitySteps,
        long markPriceTicks,
        long equityUnits,
        long maintenanceMarginUnits,
        long notionalMultiplierUnits,
        long priceTickUnits,
        long settleScaleUnits) {

    public LiquidationPricingInput {
        if (contractType == null) {
            throw new IllegalArgumentException("contractType is required");
        }
        if (signedQuantitySteps == 0) {
            throw new IllegalArgumentException("signedQuantitySteps must be non-zero");
        }
        if (markPriceTicks <= 0 || notionalMultiplierUnits <= 0 || priceTickUnits <= 0 || settleScaleUnits <= 0) {
            throw new IllegalArgumentException("price and contract unit fields must be positive");
        }
        if (maintenanceMarginUnits < 0) {
            throw new IllegalArgumentException("maintenanceMarginUnits must be non-negative");
        }
    }
}
