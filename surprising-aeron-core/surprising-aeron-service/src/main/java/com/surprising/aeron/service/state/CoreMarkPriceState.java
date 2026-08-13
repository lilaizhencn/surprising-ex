package com.surprising.aeron.service.state;

public record CoreMarkPriceState(String symbol, long instrumentVersion, long markPriceTicks, long priceSequence) {
    public CoreMarkPriceState {
        symbol = OrderReservation.normalizeSymbol(symbol);
        if (instrumentVersion <= 0 || markPriceTicks <= 0 || priceSequence <= 0) {
            throw new IllegalArgumentException("invalid mark price state");
        }
    }
}
