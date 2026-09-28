package com.surprising.trading.api.model;

import java.util.List;

public record AdminCancelOrdersPreviewResponse(
        Long userId,
        String instrumentId,
        int matched,
        int sampleSize,
        long totalRemainingQuantitySteps,
        int buyOrders,
        int sellOrders,
        List<OrderResponse> orders) {
}
