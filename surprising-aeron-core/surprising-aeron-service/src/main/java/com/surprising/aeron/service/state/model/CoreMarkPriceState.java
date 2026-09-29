package com.surprising.aeron.service.state.model;

import com.surprising.aeron.service.state.OrderReservation;

public record CoreMarkPriceState(String instrumentId, long markPriceTicks,
                                 long indexPriceTicks, long forwardPriceTicks, long priceSequence,
                                 long generatedAtEpochMillis, long lastPriceTicks) {
    public CoreMarkPriceState(String instrumentId, long markPriceTicks,
                              long indexPriceTicks, long forwardPriceTicks, long priceSequence,
                              long generatedAtEpochMillis) {
        this(instrumentId, markPriceTicks, indexPriceTicks, forwardPriceTicks,
                priceSequence, generatedAtEpochMillis, 0);
    }
    public CoreMarkPriceState {
        instrumentId = OrderReservation.requireInstrumentId(instrumentId);
        if (markPriceTicks <= 0 || indexPriceTicks < 0 || forwardPriceTicks < 0 || lastPriceTicks < 0
                || priceSequence <= 0 || generatedAtEpochMillis <= 0) {
            throw new IllegalArgumentException("invalid mark price state");
        }
    }

    public CoreMarkPriceState(String instrumentId, long markPriceTicks, long priceSequence,
                              long generatedAtEpochMillis) {
        this(instrumentId, markPriceTicks, 0, 0, priceSequence, generatedAtEpochMillis);
    }
}
