package com.surprising.candlestick.api.model;

import java.util.List;

public record CandleQueryResponse(
        String instrumentId,
        String period,
        int limit,
        List<CandleResponse> candles) {
}
