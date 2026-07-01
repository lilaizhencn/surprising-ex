package com.surprising.trading.api.model;

import java.time.Instant;

public record FeeTierResponse(
        String tierCode,
        FeeScheduleSourceType sourceType,
        FeeTierQualificationMode qualificationMode,
        long min30dVolumeUnits,
        long minAssetBalanceUnits,
        long makerFeeRatePpm,
        long takerFeeRatePpm,
        int priority,
        FeeScheduleStatus status,
        Instant createdAt,
        Instant updatedAt) {
}
