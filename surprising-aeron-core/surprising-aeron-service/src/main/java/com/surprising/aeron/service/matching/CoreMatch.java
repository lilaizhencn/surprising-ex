package com.surprising.aeron.service.matching;

public record CoreMatch(
        long makerOrderId,
        long makerUserId,
        long priceTicks,
        long quantitySteps,
        boolean makerCompleted,
        boolean takerCompleted) {

    public CoreMatch {
        if (makerOrderId <= 0 || makerUserId <= 0 || priceTicks <= 0 || quantitySteps <= 0) {
            throw new IllegalArgumentException("invalid core match");
        }
    }
}
