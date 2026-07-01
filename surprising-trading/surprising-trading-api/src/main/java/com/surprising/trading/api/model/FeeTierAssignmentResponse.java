package com.surprising.trading.api.model;

import java.time.Instant;

public record FeeTierAssignmentResponse(
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
}
