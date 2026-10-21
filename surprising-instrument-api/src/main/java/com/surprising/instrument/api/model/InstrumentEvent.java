package com.surprising.instrument.api.model;

import java.time.Instant;
import com.surprising.product.api.ProductLine;

public record InstrumentEvent(
        int instrumentId,
        String symbol,
        long changeId,
        InstrumentStatus status,
        InstrumentEventType eventType,
        Instant eventTime,
        InstrumentResponse snapshot,
        ProductLine productLine,
        long sequence,
        java.util.Map<String, Long> assetScales) {

    public InstrumentEvent(int instrumentId, String symbol, long changeId, InstrumentStatus status,
                           InstrumentEventType eventType, Instant eventTime, InstrumentResponse snapshot,
                           ProductLine productLine, long sequence) {
        this(instrumentId, symbol, changeId, status, eventType, eventTime, snapshot, productLine, sequence,
                java.util.Map.of());
    }

    public InstrumentEvent {
        assetScales = assetScales == null ? java.util.Map.of() : java.util.Map.copyOf(assetScales);
        if (instrumentId <= 0) throw new IllegalArgumentException("instrumentId must be positive");
        if (productLine == null) {
            throw new IllegalArgumentException("productLine is required");
        }
        if (sequence <= 0L) {
            throw new IllegalArgumentException("sequence must be positive");
        }
    }
}
