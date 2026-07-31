package com.surprising.trading.matching.model;

import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.TimeInForce;
import java.time.Instant;

public record RecoveredOrderBookOrder(
        long orderId,
        long userId,
        String symbol,
        long instrumentVersion,
        OrderSide side,
        TimeInForce timeInForce,
        long priceTicks,
        long remainingQuantitySteps,
        Instant createdAt) {

    /** 兼容旧测试和恢复调用方；生产查询会提供真实的合约规格标识。 */
    public RecoveredOrderBookOrder(long orderId,
                                   long userId,
                                   String symbol,
                                   OrderSide side,
                                   TimeInForce timeInForce,
                                   long priceTicks,
                                   long remainingQuantitySteps,
                                   Instant createdAt) {
        this(orderId, userId, symbol, 1L, side, timeInForce, priceTicks, remainingQuantitySteps, createdAt);
    }
}
