package com.surprising.instrument.api.model;

import java.time.Instant;

public record InstrumentEvent(
        String symbol,
        long version,
        InstrumentStatus status,
        InstrumentEventType eventType,
        Instant eventTime,
        InstrumentResponse snapshot) {
}
