package com.surprising.liquidation.provider.service;

import com.surprising.trading.api.model.OrderSide;

public final class LiquidationSideResolver {

    private LiquidationSideResolver() {
    }

    public static OrderSide closeSide(long signedQuantitySteps) {
        if (signedQuantitySteps > 0) {
            return OrderSide.SELL;
        }
        if (signedQuantitySteps < 0) {
            return OrderSide.BUY;
        }
        throw new IllegalArgumentException("signedQuantitySteps must not be zero");
    }

    public static long closeQuantity(long signedQuantitySteps) {
        return Math.absExact(signedQuantitySteps);
    }
}
