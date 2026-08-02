package com.surprising.trading.api.model;

import com.surprising.product.api.ProductLine;
import java.time.Instant;

/** 用户杠杆配置事实事件；订单节点通过 Kafka 增量更新 JVM 快照。 */
public record LeverageSettingEvent(
        int schemaVersion,
        long eventId,
        LeverageSettingRequest setting,
        Instant eventTime) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public LeverageSettingEvent {
        if (schemaVersion <= 0 || eventId <= 0L) {
            throw new IllegalArgumentException("杠杆事件版本和编号必须为正数");
        }
        if (setting == null || setting.productLine() == null || setting.userId() <= 0L
                || setting.symbol() == null || setting.symbol().isBlank() || setting.leveragePpm() <= 0L) {
            throw new IllegalArgumentException("杠杆事件事实无效");
        }
        ProductLine productLine = setting.productLine();
        if (productLine == ProductLine.SPOT) {
            throw new IllegalArgumentException("现货不支持杠杆配置");
        }
        eventTime = eventTime == null ? Instant.now() : eventTime;
    }
}
