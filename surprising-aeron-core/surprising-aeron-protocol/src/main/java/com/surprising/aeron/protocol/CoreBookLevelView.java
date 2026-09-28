package com.surprising.aeron.protocol;

public record CoreBookLevelView(
        String instrumentId,
        CoreOrderSide side,
        long priceTicks,
        long quantitySteps,
        long orderCount) {

    public CoreBookLevelView {
        if (instrumentId == null || instrumentId.isBlank() || side == null || priceTicks <= 0
                || quantitySteps <= 0 || orderCount <= 0) {
            throw new IllegalArgumentException("invalid Core book level view");
        }
    }
}
