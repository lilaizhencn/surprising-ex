package com.surprising.aeron.protocol;

import com.surprising.product.api.ProductLine;

public record CoreOrderStateView(
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
        String status,
        long revision) {
}
