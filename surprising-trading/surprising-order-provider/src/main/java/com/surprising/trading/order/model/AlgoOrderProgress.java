package com.surprising.trading.order.model;

public record AlgoOrderProgress(
        long executedQuantitySteps,
        long activeQuantitySteps,
        int childOrderCount,
        int activeChildOrderCount,
        int nextSliceIndex) {
}
