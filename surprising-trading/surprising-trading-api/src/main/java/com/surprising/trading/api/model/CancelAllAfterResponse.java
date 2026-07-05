package com.surprising.trading.api.model;

import java.time.Instant;

public record CancelAllAfterResponse(
        long userId,
        String symbol,
        long countdownMs,
        boolean active,
        Instant triggerAt,
        Instant updatedAt,
        int canceledOrders,
        int canceledTriggerOrders) {
}
