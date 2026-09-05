package com.surprising.price.index.model;

import com.surprising.price.api.model.QuoteTransport;
import com.surprising.price.api.model.SourceStatus;
import java.math.BigDecimal;
import java.time.Instant;

public record SourceQuote(
        String source,
        String sourceSymbol,
        BigDecimal price,
        BigDecimal bidPrice,
        BigDecimal askPrice,
        BigDecimal configuredWeight,
        SourceStatus status,
        String reason,
        Instant sourceTime,
        Instant receivedAt,
        Long latencyMillis,
        QuoteTransport transport) {

    public boolean healthy() {
        return status == SourceStatus.HEALTHY && price != null && price.signum() > 0;
    }
}
