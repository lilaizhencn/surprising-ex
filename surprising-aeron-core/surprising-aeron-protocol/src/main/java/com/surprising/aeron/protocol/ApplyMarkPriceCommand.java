package com.surprising.aeron.protocol;

public record ApplyMarkPriceCommand(String symbol, long instrumentVersion, long markPriceTicks, long priceSequence,
                                    long generatedAtEpochMillis) {
    public ApplyMarkPriceCommand {
        if (symbol == null || symbol.isBlank() || instrumentVersion <= 0
                || markPriceTicks <= 0 || priceSequence <= 0 || generatedAtEpochMillis <= 0) {
            throw new IllegalArgumentException("invalid mark price command");
        }
    }
}
