package com.surprising.trading.api.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record AdminCancelBySymbolRequest(
        @NotBlank @Size(max = 64) String symbol,
        @Positive @Max(1000) Integer limit,
        @Size(max = 500) String reason) {
}
