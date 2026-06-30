package com.surprising.price.api.model;

import java.util.List;

public record MarkPriceQueryResponse(
        String symbol,
        int limit,
        List<MarkPriceResponse> prices) {
}
