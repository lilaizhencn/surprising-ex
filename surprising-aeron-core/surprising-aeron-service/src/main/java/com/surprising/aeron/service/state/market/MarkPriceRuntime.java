package com.surprising.aeron.service.state.market;

import com.surprising.aeron.service.state.instrument.CoreInstrument;

public record MarkPriceRuntime(int symbolId, CoreInstrument instrument, long markPriceTicks,
                               long indexPriceTicks, long forwardPriceTicks, long priceSequence,
                               long generatedAtEpochMillis) {
    public MarkPriceRuntime {
        if (symbolId < 0 || instrument == null || markPriceTicks <= 0
                || indexPriceTicks < 0 || forwardPriceTicks < 0
                || (indexPriceTicks == 0) != (forwardPriceTicks == 0) || priceSequence <= 0
                || generatedAtEpochMillis <= 0) {
            throw new IllegalArgumentException("invalid runtime mark price");
        }
    }

    public MarkPriceRuntime(int symbolId, CoreInstrument instrument, long markPriceTicks, long priceSequence,
                            long generatedAtEpochMillis) {
        this(symbolId, instrument, markPriceTicks, 0, 0, priceSequence, generatedAtEpochMillis);
    }
}
