package com.surprising.trading.api.model;

import java.time.Instant;

public record AdminTriggerOrderTimelineEvent(
        String eventType,
        TriggerOrderStatus status,
        Long triggerSequence,
        Long triggerPriceTicks,
        Long placedOrderId,
        String reason,
        String traceId,
        Instant eventTime) {
}
