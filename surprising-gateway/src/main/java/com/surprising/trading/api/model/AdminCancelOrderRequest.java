package com.surprising.trading.api.model;

import jakarta.validation.constraints.Size;

public record AdminCancelOrderRequest(
        @Size(max = 500) String reason) {
}
