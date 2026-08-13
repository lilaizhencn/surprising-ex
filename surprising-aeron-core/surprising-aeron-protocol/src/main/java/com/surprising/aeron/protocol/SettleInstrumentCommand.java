package com.surprising.aeron.protocol;

public record SettleInstrumentCommand(
        long settlementId,
        String symbol,
        long instrumentVersion,
        long settlementPriceTicks,
        long optionCashUnitsPerContract) {
    public SettleInstrumentCommand {
        if (settlementId <= 0 || symbol == null || symbol.isBlank() || instrumentVersion <= 0
                || settlementPriceTicks < 0 || optionCashUnitsPerContract < 0) {
            throw new IllegalArgumentException("invalid instrument settlement command");
        }
    }
}
