package com.surprising.price.api.model;

import java.math.BigDecimal;
import java.time.Instant;

public record ExchangeRateResponse(
        String baseCurrency,
        String quoteCurrency,
        BigDecimal rate,
        String provider,
        Instant rateTime,
        Instant updatedAt) {
}
