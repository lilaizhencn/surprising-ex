package com.surprising.aeron.protocol;

public record ApplyFundingCommand(long settlementId, String symbol, long fundingRatePpm,
                                  long cursorUserId, int maxUsers) {
    public static final int DEFAULT_MAX_USERS = 256;

    public ApplyFundingCommand(long settlementId, String symbol, long fundingRatePpm) {
        this(settlementId, symbol, fundingRatePpm, 0, DEFAULT_MAX_USERS);
    }

    public ApplyFundingCommand {
        if (settlementId <= 0 || symbol == null || symbol.isBlank()
                || Math.absExact(fundingRatePpm) > 1_000_000 || cursorUserId < 0
                || maxUsers < 1 || maxUsers > 4096) {
            throw new IllegalArgumentException("invalid funding command");
        }
    }
}
