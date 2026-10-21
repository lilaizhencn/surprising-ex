package com.surprising.trading.api.model;

public record AlgoOrderBatchItemResponse(
        int index,
        boolean success,
        String message,
        AlgoOrderResponse algoOrder) {
}
