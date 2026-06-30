package com.surprising.price.api.model;

import java.math.BigDecimal;
import java.time.Instant;

public record IndexComponentSnapshot(
        String source,
        String sourceSymbol,
        BigDecimal price,
        BigDecimal bidPrice,
        BigDecimal askPrice,
        BigDecimal configuredWeight,
        BigDecimal effectiveWeight,
        SourceStatus status,
        String reason,
        Instant sourceTime,
        Instant receivedAt,
        Long latencyMillis) {
}
