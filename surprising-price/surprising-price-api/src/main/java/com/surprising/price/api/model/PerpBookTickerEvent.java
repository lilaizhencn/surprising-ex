package com.surprising.price.api.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.Instant;

public record PerpBookTickerEvent(
        @NotBlank String symbol,
        @NotNull @Positive BigDecimal bestBidPrice,
        @NotNull @Positive BigDecimal bestAskPrice,
        @PositiveOrZero long sequence,
        @NotNull Instant eventTime) {
}
