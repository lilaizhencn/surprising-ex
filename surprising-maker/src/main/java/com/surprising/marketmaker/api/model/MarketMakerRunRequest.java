package com.surprising.marketmaker.api.model;

import com.surprising.product.api.ProductLine;
import jakarta.validation.constraints.Size;

public record MarketMakerRunRequest(
        @Size(max = 64) String strategyId,
        @Size(max = 64) String symbol,
        ProductLine productLine) {

    public MarketMakerRunRequest(String strategyId, String symbol) {
        this(strategyId, symbol, null);
    }
}
