package com.surprising.marketmaker.provider.model;

import com.surprising.trading.api.model.OrderSide;
import java.time.Instant;

/** One third-party taker print, already scaled into this instrument's ticks and steps. */
public record ExternalTrade(String tradeId, OrderSide takerSide, long priceTicks, long quantitySteps, Instant receivedAt) {
    public ExternalTrade {
        if (takerSide == null || priceTicks <= 0 || quantitySteps <= 0 || receivedAt == null) {
            throw new IllegalArgumentException("external trade requires a side, price, quantity and time");
        }
    }
}
