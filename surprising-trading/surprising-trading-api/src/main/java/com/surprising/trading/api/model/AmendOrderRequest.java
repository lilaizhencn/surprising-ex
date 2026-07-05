package com.surprising.trading.api.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record AmendOrderRequest(
        @Positive long userId,
        @Positive long orderId,
        @Size(max = 64) String newClientOrderId,
        @Min(0) Long priceTicks,
        @Positive Long quantitySteps,
        TimeInForce timeInForce,
        Boolean postOnly) {
}
