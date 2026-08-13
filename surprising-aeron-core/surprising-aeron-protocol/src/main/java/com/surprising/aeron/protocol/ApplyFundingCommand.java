package com.surprising.aeron.protocol;

public record ApplyFundingCommand(long settlementId, String symbol, long instrumentVersion, long fundingRatePpm) {
    public ApplyFundingCommand {
        if (settlementId <= 0 || symbol == null || symbol.isBlank() || instrumentVersion <= 0
                || Math.absExact(fundingRatePpm) > 1_000_000) {
            throw new IllegalArgumentException("invalid funding command");
        }
    }
}
