package com.surprising.liquidation.api.model;

import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import java.time.Instant;

public record LiquidationOrderResponse(
        long liquidationOrderId,
        long candidateId,
        long orderId,
        long userId,
        String symbol,
        MarginMode marginMode,
        OrderSide side,
        long quantitySteps,
        LiquidationOrderStatus status,
        String reason,
        Instant createdAt) {

    public LiquidationOrderResponse {
        marginMode = MarginMode.defaultIfNull(marginMode);
    }

    public LiquidationOrderResponse(long liquidationOrderId,
                                    long candidateId,
                                    long orderId,
                                    long userId,
                                    String symbol,
                                    OrderSide side,
                                    long quantitySteps,
                                    LiquidationOrderStatus status,
                                    String reason,
                                    Instant createdAt) {
        this(liquidationOrderId, candidateId, orderId, userId, symbol, MarginMode.CROSS, side, quantitySteps,
                status, reason, createdAt);
    }
}
