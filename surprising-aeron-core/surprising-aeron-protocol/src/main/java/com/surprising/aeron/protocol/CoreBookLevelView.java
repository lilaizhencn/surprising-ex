package com.surprising.aeron.protocol;

public record CoreBookLevelView(
        String symbol,
        CoreOrderSide side,
        long priceTicks,
        long quantitySteps,
        long orderCount) {

    public CoreBookLevelView {
        if (symbol == null || symbol.isBlank() || side == null || priceTicks <= 0
                || quantitySteps <= 0 || orderCount <= 0) {
            throw new IllegalArgumentException("invalid Core book level view");
        }
    }
}
