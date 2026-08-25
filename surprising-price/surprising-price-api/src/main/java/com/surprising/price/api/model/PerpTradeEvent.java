package com.surprising.price.api.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Last-trade input for mark price calculation.
 *
 * <p>This is the internal calculation and audit representation after canonical matcher trades
 * have been converted from their fixed-point wire encoding.</p>
 */
public record PerpTradeEvent(
        @NotBlank String symbol,
        String tradeId,
        @PositiveOrZero long sequence,
        @NotNull Instant tradeTime,
        @NotNull @Positive BigDecimal price,
        @NotNull @Positive BigDecimal quantity,
        String side) {
}
