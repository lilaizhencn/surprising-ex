package com.surprising.trading.trigger.model;

import java.time.Instant;

public record MarkTrigger(
        String symbol,
        long sequence,
        Instant eventTime) {
}
