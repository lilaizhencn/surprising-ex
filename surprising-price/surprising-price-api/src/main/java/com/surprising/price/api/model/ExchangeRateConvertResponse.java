package com.surprising.price.api.model;

import java.math.BigDecimal;
import java.time.Instant;

public record ExchangeRateConvertResponse(
        String fromCurrency,
        String toCurrency,
        BigDecimal amount,
        BigDecimal convertedAmount,
        BigDecimal rate,
        String route,
        Instant rateTime) {
}
