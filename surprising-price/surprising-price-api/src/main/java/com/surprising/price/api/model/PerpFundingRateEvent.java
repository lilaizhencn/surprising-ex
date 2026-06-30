package com.surprising.price.api.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;

public record PerpFundingRateEvent(
        @NotBlank String symbol,
        @NotNull BigDecimal fundingRate,
        @NotNull Instant nextFundingTime,
        int fundingIntervalHours,
        long sequence,
        @NotNull Instant eventTime) {
}
