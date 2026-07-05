package com.surprising.trading.api.model;

import java.time.Instant;

public record AlgoOrderResponse(
        long algoOrderId,
        long userId,
        String clientAlgoOrderId,
        String symbol,
        AlgoOrderType algoType,
        OrderSide side,
        long priceTicks,
        long quantitySteps,
        long childQuantitySteps,
        long intervalSeconds,
        long durationSeconds,
        MarginMode marginMode,
        PositionSide positionSide,
        boolean reduceOnly,
        boolean postOnly,
        TimeInForce timeInForce,
        AlgoOrderStatus status,
        long executedQuantitySteps,
        long activeQuantitySteps,
        int childOrderCount,
        Long currentOrderId,
        String rejectReason,
        Instant startAt,
        Instant nextSliceAt,
        Instant completedAt,
        Instant createdAt,
        Instant updatedAt) {

    public AlgoOrderResponse {
        marginMode = MarginMode.defaultIfNull(marginMode);
        positionSide = PositionSide.defaultIfNull(positionSide);
    }
}
