package com.surprising.trading.api.model;

import com.surprising.product.api.ProductLine;
import java.time.Instant;

/**
 * 费率计划增量事件。
 *
 * <p>事件携带完整记录，消费方可以幂等重放并在本地重新计算用户最终费率。</p>
 */
public record FeeScheduleEvent(
        int schemaVersion,
        ProductLine productLine,
        long feeScheduleId,
        FeeScheduleEventType eventType,
        FeeScheduleResponse schedule,
        Instant eventTime) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public FeeScheduleEvent {
        if (schemaVersion <= 0) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        if (productLine == null) {
            throw new IllegalArgumentException("productLine is required");
        }
        if (feeScheduleId <= 0) {
            throw new IllegalArgumentException("feeScheduleId must be positive");
        }
        if (eventType == null) {
            throw new IllegalArgumentException("eventType is required");
        }
        if (schedule == null || schedule.feeScheduleId() != feeScheduleId
                || schedule.productLine() != productLine) {
            throw new IllegalArgumentException("fee schedule event identity does not match payload");
        }
        if (eventType == FeeScheduleEventType.DISABLED && schedule.status() != FeeScheduleStatus.DISABLED) {
            throw new IllegalArgumentException("disabled fee schedule event must carry DISABLED status");
        }
        if (eventType == FeeScheduleEventType.UPSERTED && schedule.status() == FeeScheduleStatus.DISABLED) {
            throw new IllegalArgumentException("upserted fee schedule event cannot carry DISABLED status");
        }
        eventTime = eventTime == null ? Instant.now() : eventTime;
    }
}
