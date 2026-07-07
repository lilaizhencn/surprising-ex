package com.surprising.trading.order.model;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.AlgoOrderStatus;
import com.surprising.trading.api.model.AlgoOrderType;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.api.model.TimeInForce;
import java.time.Instant;

public record AlgoOrderRecord(
        long algoOrderId,
        ProductLine productLine,
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

    public AlgoOrderRecord {
        productLine = productLine == null ? ProductLine.LINEAR_PERPETUAL : productLine;
    }

    public AlgoOrderRecord(long algoOrderId,
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
        this(algoOrderId, ProductLine.LINEAR_PERPETUAL, userId, clientAlgoOrderId, symbol, algoType, side,
                priceTicks, quantitySteps, childQuantitySteps, intervalSeconds, durationSeconds, marginMode,
                positionSide, reduceOnly, postOnly, timeInForce, status, currentOrderId, rejectReason, traceId,
                startAt, nextSliceAt, completedAt, createdAt, updatedAt);
    }
}
