package com.surprising.trading.api.model;

public record TriggerOrderBatchItemResponse(
        int index,
        boolean success,
        String message,
        TriggerOrderResponse order) {
}
