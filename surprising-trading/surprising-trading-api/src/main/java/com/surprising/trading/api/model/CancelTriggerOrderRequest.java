package com.surprising.trading.api.model;

import jakarta.validation.constraints.Positive;

public record CancelTriggerOrderRequest(
        @Positive long userId,
        @Positive long triggerOrderId) {
}
