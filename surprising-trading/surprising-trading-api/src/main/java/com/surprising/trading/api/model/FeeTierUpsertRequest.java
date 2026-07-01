package com.surprising.trading.api.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record FeeTierUpsertRequest(
        @NotBlank @Size(max = 32) String tierCode,
        FeeScheduleSourceType sourceType,
        FeeTierQualificationMode qualificationMode,
        long min30dVolumeUnits,
        long minAssetBalanceUnits,
        long makerFeeRatePpm,
        long takerFeeRatePpm,
        int priority,
        FeeScheduleStatus status) {
}
