package com.surprising.aeron.service.state;

import com.surprising.product.api.ProductLine;
import com.surprising.aeron.protocol.CoreOrderSide;

public record CoreOrderState(
        long orderId,
        ProductLine productLine,
        long userId,
        String symbol,
        long instrumentVersion,
        CoreOrderSide side,
        long priceTicks,
        long quantitySteps,
        long executedQuantitySteps,
        long remainingQuantitySteps,
        boolean reduceOnly,
        CoreOrderStatus status,
        long revision) {

    public CoreOrderState {
        if (orderId <= 0 || productLine == null || userId <= 0 || instrumentVersion <= 0
                || side == null || priceTicks < 0
                || quantitySteps <= 0 || executedQuantitySteps < 0 || remainingQuantitySteps < 0
                || Math.addExact(executedQuantitySteps, remainingQuantitySteps) != quantitySteps
                || status == null || revision <= 0) {
            throw new IllegalArgumentException("invalid order state");
        }
        symbol = OrderReservation.normalizeSymbol(symbol);
        if (status == CoreOrderStatus.OPEN && remainingQuantitySteps == 0) {
            throw new IllegalArgumentException("open order must have remaining quantity");
        }
    }

    public CoreOrderState cancel() {
        if (status.terminal()) {
            return this;
        }
        return new CoreOrderState(orderId, productLine, userId, symbol, instrumentVersion,
                side, priceTicks, quantitySteps,
                executedQuantitySteps, remainingQuantitySteps, reduceOnly, CoreOrderStatus.CANCELED,
                Math.incrementExact(revision));
    }
}
