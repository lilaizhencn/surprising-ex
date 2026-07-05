package com.surprising.trading.api.model;

import java.util.List;

public record AlgoOrderBatchResponse(
        int requested,
        int completed,
        int failed,
        List<AlgoOrderBatchItemResponse> results) {
}
