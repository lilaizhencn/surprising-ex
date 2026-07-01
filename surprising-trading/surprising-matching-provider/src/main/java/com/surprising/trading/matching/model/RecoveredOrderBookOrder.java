package com.surprising.trading.matching.model;

import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.TimeInForce;
import java.time.Instant;

public record RecoveredOrderBookOrder(
        long orderId,
        long userId,
        String symbol,
        OrderSide side,
        TimeInForce timeInForce,
        long priceTicks,
        long remainingQuantitySteps,
        Instant createdAt) {
}
