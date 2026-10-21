package com.surprising.instrument.api.model;

import com.surprising.product.api.ProductLine;
import java.time.Instant;

public record InstrumentLifecycleDrainEvent(
        int schemaVersion,
        String instrumentId,
        long instrumentChangeId,
        ProductLine productLine,
        InstrumentLifecycleDrainComponent component,
        Instant readyAt) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public InstrumentLifecycleDrainEvent {
        com.surprising.product.api.InstrumentIds.parse(instrumentId);
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("不支持的生命周期清理事件版本: " + schemaVersion);
        }
        if (instrumentId == null || instrumentId.isBlank() || instrumentChangeId <= 0
                || productLine == null || component == null || readyAt == null) {
            throw new IllegalArgumentException("生命周期清理事件字段不完整");
        }
    }
}
