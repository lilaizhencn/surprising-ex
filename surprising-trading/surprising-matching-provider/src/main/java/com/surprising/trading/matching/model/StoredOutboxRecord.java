package com.surprising.trading.matching.model;

import java.time.Instant;

public record StoredOutboxRecord(
        long id,
        String topic,
        String eventKey,
        String payload,
        Instant nextAttemptAt) {
}
