package com.surprising.trading.api.model;

import java.time.Instant;

public record OrderEvent(
        long eventId,
        long orderId,
        long userId,
        String instrumentId,
        OrderEventType eventType,
        OrderStatus status,
        String reason,
        Instant eventTime,
        String traceId) {

    public OrderEvent(long eventId,
                      long orderId,
                      long userId,
                      String instrumentId,
                      OrderEventType eventType,
                      OrderStatus status,
                      String reason,
                      Instant eventTime) {
        this(eventId, orderId, userId, instrumentId, eventType, status, reason, eventTime, null);
    }
}
