package com.surprising.aeron.protocol;

public record ApplyMarkPriceCommand(String instrumentId, long markPriceTicks,
                                    long indexPriceTicks, long forwardPriceTicks, long priceSequence,
                                    long generatedAtEpochMillis, long lastPriceTicks) {
    public ApplyMarkPriceCommand(String instrumentId, long markPriceTicks,
                                 long indexPriceTicks, long forwardPriceTicks, long priceSequence,
                                 long generatedAtEpochMillis) {
        this(instrumentId, markPriceTicks, indexPriceTicks, forwardPriceTicks,
                priceSequence, generatedAtEpochMillis, 0);
    }
    public ApplyMarkPriceCommand {
        if (instrumentId == null || instrumentId.isBlank()
                || markPriceTicks <= 0 || indexPriceTicks < 0 || forwardPriceTicks < 0
                || lastPriceTicks < 0
                || priceSequence <= 0 || generatedAtEpochMillis <= 0) {
            throw new IllegalArgumentException("invalid mark price command");
        }
    }

    public ApplyMarkPriceCommand(String instrumentId, long markPriceTicks,
                                 long priceSequence, long generatedAtEpochMillis) {
        this(instrumentId, markPriceTicks, 0, 0, priceSequence, generatedAtEpochMillis);
    }
}
