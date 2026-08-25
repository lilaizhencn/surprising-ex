package com.surprising.aeron.service.matching;

import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CoreTimeInForce;

public record CoreMatchingOrder(long orderId, String symbol, CoreOrderSide side, CoreOrderType orderType,
                                CoreTimeInForce timeInForce, long matchingPriceTicks, long quantitySteps) {
    public CoreMatchingOrder {
        if (orderId <= 0 || symbol == null || symbol.isBlank() || side == null || orderType == null
                || timeInForce == null || matchingPriceTicks <= 0 || quantitySteps <= 0) {
            throw new IllegalArgumentException("invalid Core matching order");
        }
    }
}
