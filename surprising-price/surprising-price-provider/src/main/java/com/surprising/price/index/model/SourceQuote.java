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
        QuoteTransport transport,
        com.surprising.price.api.model.OptionPriceReference optionReference) {
    public SourceQuote(String source, String sourceSymbol, BigDecimal price, BigDecimal bidPrice,
            BigDecimal askPrice, BigDecimal configuredWeight, SourceStatus status, String reason,
            Instant sourceTime, Instant receivedAt, Long latencyMillis, QuoteTransport transport) {
        this(source, sourceSymbol, price, bidPrice, askPrice, configuredWeight, status, reason,
                sourceTime, receivedAt, latencyMillis, transport, null);
    }

    public boolean healthy() {
        return status == SourceStatus.HEALTHY && price != null && price.signum() > 0;
    }
}
