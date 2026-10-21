package com.surprising.trading.api.model;

public record OrderBatchItemResponse(
        int index,
        boolean success,
        String message,
        OrderResponse order) {
}
