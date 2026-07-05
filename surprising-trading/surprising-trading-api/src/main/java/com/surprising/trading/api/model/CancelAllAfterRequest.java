package com.surprising.trading.api.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CancelAllAfterRequest(
        @Positive long userId,
        @Size(max = 64) String symbol,
        @Min(0) @Max(120_000) Long countdownMs) {
}
