package com.surprising.aeron.protocol;

public record ApplyMarkPriceCommand(String symbol, long instrumentVersion, long markPriceTicks, long priceSequence) {
    public ApplyMarkPriceCommand {
        if (symbol == null || symbol.isBlank() || instrumentVersion <= 0
                || markPriceTicks <= 0 || priceSequence <= 0) {
            throw new IllegalArgumentException("invalid mark price command");
        }
    }
}
