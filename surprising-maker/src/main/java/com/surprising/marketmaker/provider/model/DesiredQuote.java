package com.surprising.marketmaker.provider.model;

import com.surprising.trading.api.model.OrderSide;

public record DesiredQuote(
        OrderSide side,
        int level,
        long priceTicks,
        long quantitySteps) {
}
