package com.surprising.aeron.service.state.model;

import com.surprising.aeron.protocol.UpsertFeePolicyCommand;
import com.surprising.aeron.service.state.order.OrderReservation;

/**
 * 某个用户的一条手续费策略，可对所有币对或指定币对生效。构造器检查费率及时间范围，排序方法决定多条策略竞争时的先后顺序。
 * @param policyId 手续费策略编号，必须为正数
 * @param policyRevision 该手续费策略的修订号，必须为正数
 * @param userId 这份状态所属的用户编号，必须为正数
 * @param instrumentId 策略指定的币对编号，空值或空白表示对该用户所有币对适用
 * @param makerFeeRatePpm 挂单成交时采用的费率，单位为百万分之一，负数表示返佣
 * @param takerFeeRatePpm 吃单成交时采用的费率，单位为百万分之一，负数表示返佣
 * @param sourcePriority 策略来源的排序优先级，数值越小越先被选用
 * @param active 该费率策略是否启用；停用的策略不参与生效判断
 * @param effectiveFromEpochMillis 策略开始生效的 Unix 毫秒时间戳，包含这个时刻
 * @param expireAtEpochMillis 策略到期的 Unix 毫秒时间戳，不包含这个时刻，0 表示不设到期时间
 */
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

    /**
     * 统一全币对策略的空编号写法，并检查费率在正负一倍内、挂单费率不高于吃单费率以及生效区间有效。
     */
    public CoreFeePolicyState {
        instrumentId = instrumentId == null || instrumentId.isBlank() ? "" : OrderReservation.requireInstrumentId(instrumentId);
        if (policyId <= 0 || policyRevision <= 0 || userId <= 0
                || makerFeeRatePpm < -1_000_000 || makerFeeRatePpm > 1_000_000
                || takerFeeRatePpm < -1_000_000 || takerFeeRatePpm > 1_000_000
                || makerFeeRatePpm > takerFeeRatePpm || sourcePriority < 0
                || effectiveFromEpochMillis <= 0 || expireAtEpochMillis < 0
                || expireAtEpochMillis > 0 && expireAtEpochMillis <= effectiveFromEpochMillis) {
            throw new IllegalArgumentException("invalid core fee policy");
        }
    }

    /**
     * 从费率配置命令提取字段并构造经过校验的策略记录，不在这里写入策略簿。
     * @param command 已经解码的新增或更新手续费策略命令
     */
    public static CoreFeePolicyState from(UpsertFeePolicyCommand command) {
        return new CoreFeePolicyState(command.policyId(), command.policyRevision(), command.userId(), command.instrumentId(),
                command.makerFeeRatePpm(), command.takerFeeRatePpm(), command.sourcePriority(), command.active(),
                command.effectiveFromEpochMillis(), command.expireAtEpochMillis());
    }

    /**
     * 同时检查启用标记、用户、币对和集群时间；生效起点包含，到期时刻不包含。
     * @param requestedUserId 本次需要匹配费率的用户编号
     * @param requestedSymbol 本次需要匹配费率的币对编号
     * @param clusterTimestamp 集群提供的当前时间，单位为 Unix 毫秒，避免用本机时钟判断生效
     */
    public boolean effective(long requestedUserId, String requestedSymbol, long clusterTimestamp) {
        return active && userId == requestedUserId
                && (instrumentId.isEmpty() || instrumentId.equals(requestedSymbol))
                && effectiveFromEpochMillis <= clusterTimestamp
                && (expireAtEpochMillis == 0 || expireAtEpochMillis > clusterTimestamp);
    }

    /**
     * 依次按来源优先级从小到大、指定币对优先、生效时间从新到旧、策略编号从大到小排序。
     * @param other 需要与当前策略比较选用顺序的另一条策略
     */
    @Override
    public int compareTo(CoreFeePolicyState other) {
        // 当前排序维度的比较结果；只有相同时才继续比较下一个维度。
        int result = Integer.compare(sourcePriority, other.sourcePriority);
        if (result != 0) return result;
        result = Boolean.compare(instrumentId.isEmpty(), other.instrumentId.isEmpty());
        if (result != 0) return result;
        result = Long.compare(other.effectiveFromEpochMillis, effectiveFromEpochMillis);
        return result != 0 ? result : Long.compare(other.policyId, policyId);
    }
}
