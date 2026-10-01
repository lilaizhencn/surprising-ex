package com.surprising.aeron.service.matching;

import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CoreTimeInForce;

public record CoreMatchingOrder(long orderId, String instrumentId, CoreOrderSide side, CoreOrderType orderType,
                                CoreTimeInForce timeInForce, long matchingPriceTicks, long quantitySteps, long maxSlippagePpm) {
    public CoreMatchingOrder {
        if (orderId <= 0 || instrumentId == null || instrumentId.isBlank() || side == null || orderType == null
                || timeInForce == null || matchingPriceTicks <= 0 || quantitySteps <= 0
                || maxSlippagePpm < 0 || maxSlippagePpm >= 1_000_000) {
            throw new IllegalArgumentException("invalid Core matching order");
        }
    }
    public CoreMatchingOrder(long orderId, String instrumentId, CoreOrderSide side, CoreOrderType orderType,
            CoreTimeInForce timeInForce, long matchingPriceTicks, long quantitySteps) {
        this(orderId, instrumentId, side, orderType, timeInForce, matchingPriceTicks, quantitySteps, 0);
    }

    public static long slippagePpm(com.surprising.product.api.ProductLine product) {
        return product == com.surprising.product.api.ProductLine.LINEAR_PERPETUAL
                || product == com.surprising.product.api.ProductLine.LINEAR_DELIVERY ? 100 : 0;
    }
}
