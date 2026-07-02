package com.surprising.marketmaker.api.model;

import jakarta.validation.constraints.Size;

public record MarketMakerRunRequest(
        @Size(max = 64) String strategyId,
        @Size(max = 64) String symbol) {
}
