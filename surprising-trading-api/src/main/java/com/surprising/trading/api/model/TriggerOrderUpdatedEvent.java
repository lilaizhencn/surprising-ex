package com.surprising.trading.api.model;

import com.surprising.product.api.ProductLine;
import java.time.Instant;

/** Complete trigger-order snapshot emitted after an authoritative status transition commits. */
public record TriggerOrderUpdatedEvent(
        long eventId,
        ProductLine productLine,
        TriggerOrderResponse order,
        Instant eventTime,
        String traceId) {

    public TriggerOrderUpdatedEvent {
        if (eventId <= 0) {
            throw new IllegalArgumentException("eventId must be positive");
        }
        if (productLine == null) {
            throw new IllegalArgumentException("productLine is required");
        }
        if (order == null) {
            throw new IllegalArgumentException("order is required");
        }
        if (eventTime == null) {
            throw new IllegalArgumentException("eventTime is required");
        }
    }
}
