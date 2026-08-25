package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.UpsertFeePolicyCommand;

public record CoreFeePolicyState(
        long policyId,
        long policyRevision,
        long userId,
        String symbol,
        long makerFeeRatePpm,
        long takerFeeRatePpm,
        int sourcePriority,
        boolean active,
        long effectiveFromEpochMillis,
        long expireAtEpochMillis) implements Comparable<CoreFeePolicyState> {

    public CoreFeePolicyState {
        symbol = symbol == null || symbol.isBlank() ? "" : OrderReservation.normalizeSymbol(symbol);
        if (policyId <= 0 || policyRevision <= 0 || userId <= 0
                || makerFeeRatePpm < -1_000_000 || makerFeeRatePpm > 1_000_000
                || takerFeeRatePpm < -1_000_000 || takerFeeRatePpm > 1_000_000
                || makerFeeRatePpm > takerFeeRatePpm || sourcePriority < 0
                || effectiveFromEpochMillis <= 0 || expireAtEpochMillis < 0
                || expireAtEpochMillis > 0 && expireAtEpochMillis <= effectiveFromEpochMillis) {
            throw new IllegalArgumentException("invalid core fee policy");
        }
    }

    static CoreFeePolicyState from(UpsertFeePolicyCommand command) {
        return new CoreFeePolicyState(command.policyId(), command.policyRevision(), command.userId(), command.symbol(),
                command.makerFeeRatePpm(), command.takerFeeRatePpm(), command.sourcePriority(), command.active(),
                command.effectiveFromEpochMillis(), command.expireAtEpochMillis());
    }

    boolean effective(long requestedUserId, String requestedSymbol, long clusterTimestamp) {
        return active && userId == requestedUserId
                && (symbol.isEmpty() || symbol.equals(requestedSymbol))
                && effectiveFromEpochMillis <= clusterTimestamp
                && (expireAtEpochMillis == 0 || expireAtEpochMillis > clusterTimestamp);
    }

    @Override
    public int compareTo(CoreFeePolicyState other) {
        int result = Integer.compare(sourcePriority, other.sourcePriority);
        if (result != 0) return result;
        result = Boolean.compare(symbol.isEmpty(), other.symbol.isEmpty());
        if (result != 0) return result;
        result = Long.compare(other.effectiveFromEpochMillis, effectiveFromEpochMillis);
        return result != 0 ? result : Long.compare(other.policyId, policyId);
    }
}
