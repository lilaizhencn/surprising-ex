package com.surprising.aeron.protocol;

public record CoreExecutionView(
        long takerOrderId,
        long makerOrderId,
        long takerUserId,
        long makerUserId,
        long priceTicks,
        long quantitySteps) {

    public CoreExecutionView {
        if (takerOrderId <= 0 || makerOrderId <= 0 || takerUserId <= 0 || makerUserId <= 0
                || takerUserId == makerUserId || priceTicks <= 0 || quantitySteps <= 0) {
            throw new IllegalArgumentException("invalid execution view");
        }
    }
}
