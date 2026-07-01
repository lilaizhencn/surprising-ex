package com.surprising.trading.api.model;

import jakarta.validation.constraints.Positive;

public record CancelOrderRequest(
        @Positive long userId,
        @Positive long orderId) {
}
