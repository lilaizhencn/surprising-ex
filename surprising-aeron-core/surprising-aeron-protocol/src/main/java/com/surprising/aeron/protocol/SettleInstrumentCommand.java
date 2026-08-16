package com.surprising.aeron.protocol;

public record SettleInstrumentCommand(
        long settlementId,
        String symbol,
        long instrumentVersion,
        long settlementPriceTicks,
        long optionCashUnitsPerContract,
        long cursorUserId,
        int maxUsers,
        long cursorOrderId,
        int maxOrders) {
    public static final int DEFAULT_MAX_USERS = 256;
    public static final int DEFAULT_MAX_ORDERS = 1_024;

    public SettleInstrumentCommand(long settlementId, String symbol, long instrumentVersion,
                                   long settlementPriceTicks, long optionCashUnitsPerContract) {
        this(settlementId, symbol, instrumentVersion, settlementPriceTicks, optionCashUnitsPerContract,
                0, DEFAULT_MAX_USERS, 0, DEFAULT_MAX_ORDERS);
    }

    public SettleInstrumentCommand(long settlementId, String symbol, long instrumentVersion,
                                   long settlementPriceTicks, long optionCashUnitsPerContract,
                                   long cursorUserId, int maxUsers) {
        this(settlementId, symbol, instrumentVersion, settlementPriceTicks, optionCashUnitsPerContract,
                cursorUserId, maxUsers, 0, DEFAULT_MAX_ORDERS);
    }

    public SettleInstrumentCommand {
        if (settlementId <= 0 || symbol == null || symbol.isBlank() || instrumentVersion <= 0
                || settlementPriceTicks < 0 || optionCashUnitsPerContract < 0 || cursorUserId < 0
                || maxUsers < 1 || maxUsers > 4096 || cursorOrderId < 0
                || maxOrders < 1 || maxOrders > DEFAULT_MAX_ORDERS) {
            throw new IllegalArgumentException("invalid instrument settlement command");
        }
    }
}
