package com.surprising.price.api.model;

import java.util.List;

public record IndexPriceQueryResponse(
        String symbol,
        int limit,
        List<IndexPriceResponse> prices) {
}
