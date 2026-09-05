package com.surprising.aeron.service.state;

public record CoreMarkPriceState(String symbol, long instrumentVersion, long markPriceTicks,
                                 long indexPriceTicks, long forwardPriceTicks, long priceSequence,
                                 long generatedAtEpochMillis) {
    public CoreMarkPriceState {
        symbol = OrderReservation.normalizeSymbol(symbol);
        if (instrumentVersion <= 0 || markPriceTicks <= 0 || indexPriceTicks < 0 || forwardPriceTicks < 0
                || (indexPriceTicks == 0) != (forwardPriceTicks == 0)
                || priceSequence <= 0 || generatedAtEpochMillis <= 0) {
            throw new IllegalArgumentException("invalid mark price state");
        }
    }

    public CoreMarkPriceState(String symbol, long instrumentVersion, long markPriceTicks, long priceSequence,
                              long generatedAtEpochMillis) {
        this(symbol, instrumentVersion, markPriceTicks, 0, 0, priceSequence, generatedAtEpochMillis);
    }
}
