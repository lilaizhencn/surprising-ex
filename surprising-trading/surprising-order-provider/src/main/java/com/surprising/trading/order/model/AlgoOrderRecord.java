package com.surprising.trading.order.model;

import com.surprising.trading.api.model.AlgoOrderStatus;
import com.surprising.trading.api.model.AlgoOrderType;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.api.model.TimeInForce;
import java.time.Instant;

public record AlgoOrderRecord(
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
        Long currentOrderId,
        String rejectReason,
        String traceId,
        Instant startAt,
        Instant nextSliceAt,
        Instant completedAt,
        Instant createdAt,
        Instant updatedAt) {
}
