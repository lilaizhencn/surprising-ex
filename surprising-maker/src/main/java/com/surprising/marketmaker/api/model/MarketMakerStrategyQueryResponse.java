package com.surprising.marketmaker.api.model;

import java.util.List;

public record MarketMakerStrategyQueryResponse(
        int count,
        List<MarketMakerStrategyResponse> strategies) {
}
