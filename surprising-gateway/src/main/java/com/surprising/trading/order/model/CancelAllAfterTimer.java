package com.surprising.trading.order.model;

import java.time.Instant;

public record CancelAllAfterTimer(
        long userId,
        String symbolScope,
        long countdownMs,
        String status,
        Instant triggerAt,
        Instant updatedAt,
        int canceledOrders,
        int canceledTriggerOrders) {
}
