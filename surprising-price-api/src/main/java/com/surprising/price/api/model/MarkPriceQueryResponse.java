package com.surprising.price.api.model;

import java.util.List;

public record MarkPriceQueryResponse(
        String instrumentId,
        int limit,
        List<MarkPriceResponse> prices) {
}
