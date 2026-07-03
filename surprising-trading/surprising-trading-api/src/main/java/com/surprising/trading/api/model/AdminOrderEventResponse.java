package com.surprising.trading.api.model;

import java.time.Instant;

public record AdminOrderEventResponse(
        long eventId,
        long orderId,
        long userId,
        String symbol,
        OrderEventType eventType,
        OrderStatus status,
        String reason,
        String traceId,
        Instant eventTime,
        Instant createdAt) {
}
