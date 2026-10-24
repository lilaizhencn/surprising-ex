package com.surprising.trading.api.model;

import java.time.Instant;

public record CancelAllAfterResponse(
        long userId,
        String instrumentId,
        boolean active,
        Instant triggerAt,
        Instant updatedAt) {
}
