package com.surprising.trading.api.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record AmendOrderRequest(
        @Positive long userId,
        @Positive long orderId,
        @NotBlank @Size(max = 64) String newClientOrderId,
        @Min(0) Long priceTicks,
        @Positive Long quantitySteps,
        TimeInForce timeInForce,
        Boolean postOnly,
        @NotBlank @Size(max = 64) String clientRequestId) {

    public AmendOrderRequest(long userId, long orderId, String newClientOrderId,
                             Long priceTicks, Long quantitySteps, TimeInForce timeInForce, Boolean postOnly) {
        this(userId, orderId, newClientOrderId, priceTicks, quantitySteps, timeInForce, postOnly,
                newClientOrderId);
    }
}
