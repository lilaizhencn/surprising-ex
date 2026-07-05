package com.surprising.trading.api.model;

public record AmendOrderResponse(
        OrderResponse originalOrder,
        OrderResponse replacementOrder,
        boolean cancelRequested,
        String message) {
}
