package com.surprising.trading.order.model;

import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.TimeInForce;
import java.time.Instant;

public record AlgoOrderChildRecord(
        long algoOrderId,
        int sliceIndex,
        long orderId,
        String clientOrderId,
        long quantitySteps,
        long priceTicks,
        OrderType orderType,
        TimeInForce timeInForce,
        OrderStatus status,
        Instant createdAt,
        Instant updatedAt) {
}
