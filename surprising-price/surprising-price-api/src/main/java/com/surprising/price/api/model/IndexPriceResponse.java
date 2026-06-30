package com.surprising.price.api.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record IndexPriceResponse(
        String symbol,
        BigDecimal indexPrice,
        long sequence,
        PriceStatus status,
        int componentCount,
        int validComponentCount,
        Instant eventTime,
        List<IndexComponentSnapshot> components) {
}
