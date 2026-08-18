package com.surprising.candlestick.api.model;

import java.util.List;

public record CandleQueryResponse(
        String symbol,
        String period,
        int limit,
        List<CandleResponse> candles) {
}
