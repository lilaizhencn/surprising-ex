package com.surprising.price.api.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Fair underlying index calculated from configured external venues.
 * Option sources additionally carry a distinct premium and same-expiry forward reference.
 *
 * <p>The event includes component snapshots so downstream risk and audit systems can explain why
 * a specific index value was produced at a specific time.</p>
 */
public record IndexPriceEvent(
        String instrumentId,
        BigDecimal indexPrice,
        long sequence,
        PriceStatus status,
        int componentCount,
        int validComponentCount,
        BigDecimal totalConfiguredWeight,
        Instant eventTime,
        List<IndexComponentSnapshot> components,
        OptionPriceReference optionReference) {
    public IndexPriceEvent(String instrumentId, BigDecimal indexPrice, long sequence, PriceStatus status,
            int componentCount, int validComponentCount, BigDecimal totalConfiguredWeight,
            Instant eventTime, List<IndexComponentSnapshot> components) {
        this(instrumentId, indexPrice, sequence, status, componentCount, validComponentCount,
                totalConfiguredWeight, eventTime, components, null);
    }
}
