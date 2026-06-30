package com.surprising.price.api.model;

import java.util.List;

public record ExchangeRateQueryResponse(
        String baseCurrency,
        int count,
        List<ExchangeRateResponse> rates) {
}
