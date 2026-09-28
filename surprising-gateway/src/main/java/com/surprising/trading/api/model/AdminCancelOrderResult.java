package com.surprising.trading.api.model;

public record AdminCancelOrderResult(
        long orderId,
        long userId,
        String instrumentId,
        OrderStatus status,
        boolean cancelRequested,
        String message,
        OrderResponse order) {
}
