package com.surprising.trading.api.model;

import java.util.List;

public record AmendOrderBatchResponse(
        int requested,
        int completed,
        int failed,
        List<AmendOrderBatchItemResponse> results) {

    public AmendOrderBatchResponse {
        results = results == null ? List.of() : List.copyOf(results);
    }
}
