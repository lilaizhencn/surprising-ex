package com.surprising.trading.api.model;

import java.time.Instant;

public record FeeScheduleUpsertRequest(
        Long feeScheduleId,
        long userId,
        String symbol,
        long makerFeeRatePpm,
        long takerFeeRatePpm,
        FeeScheduleSourceType sourceType,
        String tierCode,
        String reason,
        FeeScheduleStatus status,
        Instant effectiveTime,
        Instant expireTime) {
}
