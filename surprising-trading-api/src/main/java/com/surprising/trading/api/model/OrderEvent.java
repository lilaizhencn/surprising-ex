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
        Instant eventTime) {

}
