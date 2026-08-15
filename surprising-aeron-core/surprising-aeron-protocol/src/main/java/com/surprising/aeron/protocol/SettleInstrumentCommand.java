package com.surprising.aeron.protocol;

public record SettleInstrumentCommand(
        long settlementId,
        String symbol,
        long instrumentVersion,
        long settlementPriceTicks,
        long optionCashUnitsPerContract,
        long cursorUserId,
        int maxUsers) {
    public static final int DEFAULT_MAX_USERS = 256;

    public SettleInstrumentCommand(long settlementId, String symbol, long instrumentVersion,
                                   long settlementPriceTicks, long optionCashUnitsPerContract) {
        this(settlementId, symbol, instrumentVersion, settlementPriceTicks, optionCashUnitsPerContract,
                0, DEFAULT_MAX_USERS);
    }

    public SettleInstrumentCommand {
        if (settlementId <= 0 || symbol == null || symbol.isBlank() || instrumentVersion <= 0
                || settlementPriceTicks < 0 || optionCashUnitsPerContract < 0 || cursorUserId < 0
                || maxUsers < 1 || maxUsers > 4096) {
            throw new IllegalArgumentException("invalid instrument settlement command");
        }
    }
}
