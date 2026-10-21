package com.surprising.gateway.provider.service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * 日初及期末完整权益、外部资金按发生时汇率估值后，计算期间净收益。
 * 充值、提现及人工调账是外部资金；同一用户的产品账户划转不得传入本方法。
 * 数据完整性、历史价格和账户归属由查询入口校验，不能以零填补缺失数据。
 */
public final class DailyIncomeCalculator {
    private DailyIncomeCalculator() {}

    public static Income calculate(Instant start, Instant asOf, BigDecimal openingEquity,
                                   BigDecimal closingEquity, List<ExternalFlow> flows) {
        Objects.requireNonNull(start, "period start is required");
        Objects.requireNonNull(asOf, "valuation time is required");
        Objects.requireNonNull(openingEquity, "opening equity is required");
        Objects.requireNonNull(closingEquity, "closing equity is required");
        Objects.requireNonNull(flows, "complete external flows are required");
        if (!asOf.isAfter(start) || Duration.between(start, asOf).compareTo(Duration.ofDays(1)) > 0)
            throw new IllegalArgumentException("income period must be within one day");

        BigDecimal elapsed = BigDecimal.valueOf(Duration.between(start, asOf).toMillis());
        if (elapsed.signum() == 0) throw new IllegalArgumentException("income period must span a millisecond");
        BigDecimal netFlows = BigDecimal.ZERO;
        BigDecimal weightedFlows = BigDecimal.ZERO;
        var references = new HashSet<String>();
        for (ExternalFlow flow : flows) {
            Objects.requireNonNull(flow, "external flow is required");
            if (flow.referenceId() == null || flow.referenceId().isBlank() || !references.add(flow.referenceId()))
                throw new IllegalArgumentException("external flow reference must be unique");
            Objects.requireNonNull(flow.occurredAt(), "external flow time is required");
            Objects.requireNonNull(flow.value(), "external flow valuation is required");
            if (flow.occurredAt().isBefore(start) || flow.occurredAt().isAfter(asOf))
                throw new IllegalArgumentException("external flow is outside the income period");
            netFlows = netFlows.add(flow.value());
            BigDecimal remaining = BigDecimal.valueOf(Duration.between(flow.occurredAt(), asOf).toMillis());
            weightedFlows = weightedFlows.add(flow.value().multiply(remaining));
        }
        BigDecimal income = closingEquity.subtract(openingEquity).subtract(netFlows);
        // Modified Dietz weights funds by time in the account. A depleted or
        // negative capital base has no meaningful percentage, even when income exists.
        BigDecimal weightedCapital = openingEquity.add(weightedFlows.divide(elapsed, MathContext.DECIMAL128));
        BigDecimal percent = weightedCapital.signum() <= 0 ? null
                : income.multiply(BigDecimal.valueOf(100)).divide(weightedCapital, MathContext.DECIMAL128);
        return new Income(income, percent, netFlows);
    }

    public record ExternalFlow(String referenceId, Instant occurredAt, BigDecimal value) {}
    public record Income(BigDecimal amount, BigDecimal percent, BigDecimal netExternalFlows) {}
}
