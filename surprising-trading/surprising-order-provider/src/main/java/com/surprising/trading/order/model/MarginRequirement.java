package com.surprising.trading.order.model;

public record MarginRequirement(
        String asset,
        long initialMarginUnits) {
}
