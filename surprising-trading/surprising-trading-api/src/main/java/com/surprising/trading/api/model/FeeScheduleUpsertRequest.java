package com.surprising.trading.api.model;

import com.surprising.product.api.ProductLine;
import java.time.Instant;

public record FeeScheduleUpsertRequest(
        Long feeScheduleId,
        ProductLine productLine,
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

    public FeeScheduleUpsertRequest(Long feeScheduleId,
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
        this(feeScheduleId, ProductLine.LINEAR_PERPETUAL, userId, symbol, makerFeeRatePpm, takerFeeRatePpm,
                sourceType, tierCode, reason, status, effectiveTime, expireTime);
    }
}
