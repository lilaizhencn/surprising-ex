package com.surprising.trading.api.model;

import java.util.List;

public record OrderBatchResponse(
        int requested,
        int completed,
        int failed,
        List<OrderBatchItemResponse> results) {

    public OrderBatchResponse {
        results = results == null ? List.of() : List.copyOf(results);
    }
}
