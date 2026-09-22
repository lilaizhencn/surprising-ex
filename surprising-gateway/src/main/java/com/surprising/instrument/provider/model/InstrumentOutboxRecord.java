package com.surprising.instrument.provider.model;

import java.time.Instant;

public record InstrumentOutboxRecord(
        long id,
        String topic,
        String eventKey,
        String eventType,
        String payload,
        Instant nextAttemptAt) {
}
