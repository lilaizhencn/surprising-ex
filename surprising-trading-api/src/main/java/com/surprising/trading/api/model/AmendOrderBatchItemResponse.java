package com.surprising.trading.api.model;

public record AmendOrderBatchItemResponse(
        int index,
        boolean success,
        String message,
        AmendOrderResponse amend) {
}
