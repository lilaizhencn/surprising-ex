package com.surprising.trading.api.model;

import java.util.List;

public record TriggerOrderBatchResponse(
        int requested,
        int completed,
        int failed,
        List<TriggerOrderBatchItemResponse> results) {

    public TriggerOrderBatchResponse {
        results = results == null ? List.of() : List.copyOf(results);
    }
}
