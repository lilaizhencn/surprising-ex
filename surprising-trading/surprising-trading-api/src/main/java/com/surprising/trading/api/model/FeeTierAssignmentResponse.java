package com.surprising.trading.api.model;

import com.surprising.product.api.ProductLine;
import java.time.Instant;

public record FeeTierAssignmentResponse(
        ProductLine productLine,
        long userId,
        String tierCode,
        FeeScheduleSourceType sourceType,
        long feeScheduleId,
        long makerFeeRatePpm,
        long takerFeeRatePpm,
        long trailing30dVolumeUnits,
        long totalAssetBalanceUnits,
        FeeScheduleStatus status,
        Instant effectiveTime,
        Instant calculatedAt) {

    public FeeTierAssignmentResponse(long userId,
                                     String tierCode,
                                     FeeScheduleSourceType sourceType,
                                     long feeScheduleId,
                                     long makerFeeRatePpm,
                                     long takerFeeRatePpm,
                                     long trailing30dVolumeUnits,
                                     long totalAssetBalanceUnits,
                                     FeeScheduleStatus status,
                                     Instant effectiveTime,
                                     Instant calculatedAt) {
        this(ProductLine.LINEAR_PERPETUAL, userId, tierCode, sourceType, feeScheduleId, makerFeeRatePpm,
                takerFeeRatePpm, trailing30dVolumeUnits, totalAssetBalanceUnits, status, effectiveTime,
                calculatedAt);
    }
}
