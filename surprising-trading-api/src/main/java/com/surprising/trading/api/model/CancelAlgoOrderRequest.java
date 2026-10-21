package com.surprising.trading.api.model;

import jakarta.validation.constraints.Positive;

public record CancelAlgoOrderRequest(
        @Positive long userId,
        @Positive long algoOrderId) {
}
