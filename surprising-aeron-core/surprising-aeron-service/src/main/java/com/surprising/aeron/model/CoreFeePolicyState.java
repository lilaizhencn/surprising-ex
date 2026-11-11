package com.surprising.aeron.model;

import com.surprising.aeron.protocol.UpsertFeePolicyCommand;

/** 某个用户的一条手续费策略，可对所有币对或指定币对生效。构造器检查费率及时间范围，排序方法决定多条策略竞争时的先后顺序。 */
public record CoreFeePolicyState(
        long policyId,
        long policyRevision,
        long userId,
        String instrumentId,
        long makerFeeRatePpm,
        long takerFeeRatePpm,
        int sourcePriority,
        boolean active,
        long effectiveFromEpochMillis,
        long expireAtEpochMillis) implements Comparable<CoreFeePolicyState> {

    public CoreFeePolicyState {
        instrumentId = instrumentId == null || instrumentId.isBlank() ? "" : InstrumentId.requireValid(instrumentId);
        if (policyId <= 0 || policyRevision <= 0 || userId <= 0
                || makerFeeRatePpm < -1_000_000 || makerFeeRatePpm > 1_000_000
                || takerFeeRatePpm < -1_000_000 || takerFeeRatePpm > 1_000_000
                || makerFeeRatePpm > takerFeeRatePpm || sourcePriority < 0
                || effectiveFromEpochMillis <= 0 || expireAtEpochMillis < 0
                || expireAtEpochMillis > 0 && expireAtEpochMillis <= effectiveFromEpochMillis) {
            throw new IllegalArgumentException("invalid core fee policy");
        }
    }

    /** 从费率配置命令提取字段并构造经过校验的策略记录，不在这里写入策略簿。 */
    public static CoreFeePolicyState from(UpsertFeePolicyCommand command) {
        return new CoreFeePolicyState(command.policyId(), command.policyRevision(), command.userId(), command.instrumentId(),
                command.makerFeeRatePpm(), command.takerFeeRatePpm(), command.sourcePriority(), command.active(),
                command.effectiveFromEpochMillis(), command.expireAtEpochMillis());
    }

    /** 同时检查启用标记、用户、币对和集群时间。 */
    public boolean effective(long requestedUserId, String requestedSymbol, long clusterTimestamp) {
        return active && userId == requestedUserId
                && (instrumentId.isEmpty() || instrumentId.equals(requestedSymbol))
                && effectiveFromEpochMillis <= clusterTimestamp
                && (expireAtEpochMillis == 0 || expireAtEpochMillis > clusterTimestamp);
    }

    /** 依次按来源优先级从小到大、指定币对优先、生效时间从新到旧、策略编号从大到小排序。 */
    @Override
    public int compareTo(CoreFeePolicyState other) {
        int result = Integer.compare(sourcePriority, other.sourcePriority);
        if (result != 0) return result;
        result = Boolean.compare(instrumentId.isEmpty(), other.instrumentId.isEmpty());
        if (result != 0) return result;
        result = Long.compare(other.effectiveFromEpochMillis, effectiveFromEpochMillis);
        return result != 0 ? result : Long.compare(other.policyId, policyId);
    }
}
