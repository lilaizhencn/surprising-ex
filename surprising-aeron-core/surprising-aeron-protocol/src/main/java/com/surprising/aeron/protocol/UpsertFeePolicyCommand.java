package com.surprising.aeron.protocol;

public record UpsertFeePolicyCommand(
        long policyId,
        long policyRevision,
        long userId,
        String symbol,
        long makerFeeRatePpm,
        long takerFeeRatePpm,
        int sourcePriority,
        boolean active,
        long effectiveFromEpochMillis,
        long expireAtEpochMillis) {

    public UpsertFeePolicyCommand {
        symbol = symbol == null ? "" : symbol.trim().toUpperCase(java.util.Locale.ROOT);
        if (policyId <= 0 || policyRevision <= 0 || userId <= 0 || symbol.length() > 64
                || makerFeeRatePpm < -1_000_000 || makerFeeRatePpm > 1_000_000
                || takerFeeRatePpm < -1_000_000 || takerFeeRatePpm > 1_000_000
                || makerFeeRatePpm > takerFeeRatePpm || sourcePriority < 0
                || effectiveFromEpochMillis <= 0 || expireAtEpochMillis < 0
                || expireAtEpochMillis > 0 && expireAtEpochMillis <= effectiveFromEpochMillis) {
            throw new IllegalArgumentException("invalid fee policy command");
        }
    }
}
