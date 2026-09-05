package com.surprising.aeron.service.state;

public record MarkPriceRuntime(int symbolId, long instrumentVersion, long markPriceTicks,
                               long indexPriceTicks, long forwardPriceTicks, long priceSequence,
                               long generatedAtEpochMillis) {
    public MarkPriceRuntime {
        if (symbolId < 0 || instrumentVersion <= 0 || markPriceTicks <= 0
                || indexPriceTicks < 0 || forwardPriceTicks < 0
                || (indexPriceTicks == 0) != (forwardPriceTicks == 0) || priceSequence <= 0
                || generatedAtEpochMillis <= 0) {
            throw new IllegalArgumentException("invalid runtime mark price");
        }
    }

    public MarkPriceRuntime(int symbolId, long instrumentVersion, long markPriceTicks, long priceSequence,
                            long generatedAtEpochMillis) {
        this(symbolId, instrumentVersion, markPriceTicks, 0, 0, priceSequence, generatedAtEpochMillis);
    }
}
