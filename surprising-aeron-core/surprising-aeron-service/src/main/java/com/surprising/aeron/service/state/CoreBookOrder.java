package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CoreOrderSide;

public record CoreBookOrder(
        long orderId,
        long userId,
        String symbol,
        CoreOrderSide side,
        long priceTicks,
        long remainingQuantitySteps,
        long prioritySequence) {

    public CoreBookOrder {
        if (orderId <= 0 || userId <= 0 || side == null || priceTicks <= 0
                || remainingQuantitySteps <= 0 || prioritySequence <= 0) {
            throw new IllegalArgumentException("invalid book order");
        }
        symbol = OrderReservation.normalizeSymbol(symbol);
    }
}
