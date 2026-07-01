package com.surprising.trading.api.model;

public record OrderBookLevel(
        long priceTicks,
        long quantitySteps,
        long orderCount) {
}
