package com.surprising.trading.order.config;

import com.surprising.aeron.protocol.CoreOrderProtection;

/** 每条产品线的订单业务规则。连接、线程参数不在此处。 */
public record OrderBusinessSettings(CoreOrderProtection risk, Algo algo) {
    public OrderBusinessSettings {
        java.util.Objects.requireNonNull(risk, "risk settings required");
        java.util.Objects.requireNonNull(algo, "algo settings required");
    }
    public record Algo(boolean enabled, int claimBatchSize, long scanDelayMs,
            long minIntervalSeconds, long maxIntervalSeconds, long minDurationSeconds,
            long maxDurationSeconds, long claimLeaseMs) {
        public Algo {
            if (claimBatchSize < 1 || claimBatchSize > 1000 || scanDelayMs < 25 || scanDelayMs > 60_000
                    || minIntervalSeconds < 1 || maxIntervalSeconds < minIntervalSeconds || maxIntervalSeconds > 86_400
                    || minDurationSeconds < 1 || maxDurationSeconds < minDurationSeconds || maxDurationSeconds > 604_800
                    || claimLeaseMs < 1000 || claimLeaseMs > 600_000)
                throw new IllegalArgumentException("invalid algorithm order limits");
        }
    }
    public static OrderBusinessSettings initial() {
        return new OrderBusinessSettings(CoreOrderProtection.initial(),new Algo(true,100,250,1,86_400,5,86_400,30_000));
    }
}
