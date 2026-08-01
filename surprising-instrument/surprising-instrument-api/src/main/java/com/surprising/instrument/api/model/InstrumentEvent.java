package com.surprising.instrument.api.model;

import java.time.Instant;
import com.surprising.product.api.ProductLine;

public record InstrumentEvent(
        String symbol,
        long version,
        InstrumentStatus status,
        InstrumentEventType eventType,
        Instant eventTime,
        InstrumentResponse snapshot,
        ProductLine productLine,
        long sequence) {

    public InstrumentEvent {
        if (productLine == null) {
            throw new IllegalArgumentException("productLine is required");
        }
        if (sequence <= 0L) {
            throw new IllegalArgumentException("sequence must be positive");
        }
    }
}
