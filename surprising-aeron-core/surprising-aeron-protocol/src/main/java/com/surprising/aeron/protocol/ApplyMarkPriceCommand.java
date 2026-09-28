package com.surprising.aeron.protocol;

public record ApplyMarkPriceCommand(String instrumentId, long markPriceTicks,
                                    long indexPriceTicks, long forwardPriceTicks, long priceSequence,
                                    long generatedAtEpochMillis) {
    public ApplyMarkPriceCommand {
        if (instrumentId == null || instrumentId.isBlank()
                || markPriceTicks <= 0 || indexPriceTicks < 0 || forwardPriceTicks < 0
                || (indexPriceTicks == 0) != (forwardPriceTicks == 0)
                || priceSequence <= 0 || generatedAtEpochMillis <= 0) {
            throw new IllegalArgumentException("invalid mark price command");
        }
    }

    public ApplyMarkPriceCommand(String instrumentId, long markPriceTicks,
                                 long priceSequence, long generatedAtEpochMillis) {
        this(instrumentId, markPriceTicks, 0, 0, priceSequence, generatedAtEpochMillis);
    }
}
