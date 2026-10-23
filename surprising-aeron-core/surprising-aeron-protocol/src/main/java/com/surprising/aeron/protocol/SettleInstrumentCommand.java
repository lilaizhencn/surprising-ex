package com.surprising.aeron.protocol;

public record SettleInstrumentCommand(
        long settlementId,
        String instrumentId,
        long settlementPriceTicks,
        long cursorUserId,
        int maxUsers,
        long cursorOrderId,
        int maxOrders) {
    public static final int DEFAULT_MAX_USERS = 256;
    public static final int DEFAULT_MAX_ORDERS = 1_024;

    public SettleInstrumentCommand(long settlementId, String instrumentId,
                                   long settlementPriceTicks) {
        this(settlementId, instrumentId, settlementPriceTicks,
                0, DEFAULT_MAX_USERS, 0, DEFAULT_MAX_ORDERS);
    }

    public SettleInstrumentCommand(long settlementId, String instrumentId,
                                   long settlementPriceTicks,
                                   long cursorUserId, int maxUsers) {
        this(settlementId, instrumentId, settlementPriceTicks,
                cursorUserId, maxUsers, 0, DEFAULT_MAX_ORDERS);
    }

    public SettleInstrumentCommand {
        if (settlementId <= 0 || instrumentId == null || instrumentId.isBlank()
                || settlementPriceTicks < 0 || cursorUserId < 0
                || maxUsers < 1 || maxUsers > 4096 || cursorOrderId < 0
                || maxOrders < 1 || maxOrders > DEFAULT_MAX_ORDERS) {
            throw new IllegalArgumentException("invalid instrument settlement command");
        }
    }
}
