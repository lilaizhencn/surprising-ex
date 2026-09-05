package com.surprising.trading.order.model;

public record ReduceOnlyPosition(
        long signedQuantitySteps,
        long instrumentVersion) {
}
