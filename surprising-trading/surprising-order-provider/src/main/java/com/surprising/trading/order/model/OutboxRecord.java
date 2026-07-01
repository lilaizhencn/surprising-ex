package com.surprising.trading.order.model;

import java.time.Instant;

public record OutboxRecord(
        long id,
        String topic,
        String eventKey,
        String payload,
        Instant nextAttemptAt) {
}
