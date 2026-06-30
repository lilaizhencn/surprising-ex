package com.surprising.price.api.model;

import java.time.Instant;

public record IndexComponentEvent(
        String symbol,
        long sequence,
        Instant eventTime,
        IndexComponentSnapshot component) {
}
