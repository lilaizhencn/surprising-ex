package com.surprising.trading.api.model;

public record AdminCancelOrderResult(
        long orderId,
        long userId,
        String symbol,
        OrderStatus status,
        boolean cancelRequested,
        String message,
        OrderResponse order) {
}
