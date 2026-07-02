package com.surprising.account.provider.model;

public record LiquidationFeeContext(
        long liquidationOrderId,
        long candidateId,
        long feeRatePpm) {
}
