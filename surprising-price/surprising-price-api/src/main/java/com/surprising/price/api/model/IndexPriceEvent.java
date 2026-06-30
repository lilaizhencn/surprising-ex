package com.surprising.price.api.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Fair spot index price calculated from several external spot venues.
 *
 * <p>The event includes component snapshots so downstream risk and audit systems can explain why
 * a specific index value was produced at a specific time.</p>
 */
public record IndexPriceEvent(
        String symbol,
        BigDecimal indexPrice,
        long sequence,
        PriceStatus status,
        int componentCount,
        int validComponentCount,
        BigDecimal totalConfiguredWeight,
        Instant eventTime,
        List<IndexComponentSnapshot> components) {
}
