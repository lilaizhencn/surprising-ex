package com.surprising.aeron.protocol;

public record ApplyMarkPriceCommand(String symbol, long instrumentChangeId, long markPriceTicks,
                                    long indexPriceTicks, long forwardPriceTicks, long priceSequence,
                                    long generatedAtEpochMillis) {
    public ApplyMarkPriceCommand {
        if (symbol == null || symbol.isBlank() || instrumentChangeId <= 0
                || markPriceTicks <= 0 || indexPriceTicks < 0 || forwardPriceTicks < 0
                || (indexPriceTicks == 0) != (forwardPriceTicks == 0)
                || priceSequence <= 0 || generatedAtEpochMillis <= 0) {
            throw new IllegalArgumentException("invalid mark price command");
        }
    }

    public ApplyMarkPriceCommand(String symbol, long instrumentChangeId, long markPriceTicks,
                                 long priceSequence, long generatedAtEpochMillis) {
        this(symbol, instrumentChangeId, markPriceTicks, 0, 0, priceSequence, generatedAtEpochMillis);
    }
}
