package com.surprising.liquidation.api.model;

import com.surprising.trading.api.model.OrderSide;
import java.time.Instant;

public record LiquidationOrderResponse(
        long liquidationOrderId,
        long candidateId,
        long orderId,
        long userId,
        String symbol,
        OrderSide side,
        long quantitySteps,
        LiquidationOrderStatus status,
        String reason,
        Instant createdAt) {
}
