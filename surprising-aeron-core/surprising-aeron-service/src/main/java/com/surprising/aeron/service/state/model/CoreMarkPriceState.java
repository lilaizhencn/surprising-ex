package com.surprising.aeron.service.state.model;

import com.surprising.aeron.service.state.OrderReservation;

public record CoreMarkPriceState(String symbol, long instrumentChangeId, long markPriceTicks,
                                 long indexPriceTicks, long forwardPriceTicks, long priceSequence,
                                 long generatedAtEpochMillis) {
    public CoreMarkPriceState {
        symbol = OrderReservation.normalizeSymbol(symbol);
        if (instrumentChangeId <= 0 || markPriceTicks <= 0 || indexPriceTicks < 0 || forwardPriceTicks < 0
                || (indexPriceTicks == 0) != (forwardPriceTicks == 0)
                || priceSequence <= 0 || generatedAtEpochMillis <= 0) {
            throw new IllegalArgumentException("invalid mark price state");
        }
    }

    public CoreMarkPriceState(String symbol, long instrumentChangeId, long markPriceTicks, long priceSequence,
                              long generatedAtEpochMillis) {
        this(symbol, instrumentChangeId, markPriceTicks, 0, 0, priceSequence, generatedAtEpochMillis);
    }
}
