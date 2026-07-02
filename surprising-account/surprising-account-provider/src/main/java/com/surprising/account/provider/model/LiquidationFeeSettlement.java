package com.surprising.account.provider.model;

public record LiquidationFeeSettlement(
        long liquidationOrderId,
        long candidateId,
        long collectedFeeUnits,
        long feeRatePpm) {
}
