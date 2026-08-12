package com.surprising.risk.provider.model;

import java.time.Instant;

public record RiskOutboxRecord(
        long id,
        String topic,
        String eventKey,
        String payload,
        Instant nextAttemptAt) {
}
