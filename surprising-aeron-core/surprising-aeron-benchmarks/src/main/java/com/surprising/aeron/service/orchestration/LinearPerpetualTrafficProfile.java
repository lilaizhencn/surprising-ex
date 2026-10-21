package com.surprising.aeron.service.orchestration;

public enum LinearPerpetualTrafficProfile {
    UNIFORM,
    PARETO_80_20,
    SINGLE_HOT,
    MOSTLY_IDLE,
    MARK_PRICE_STORM;

    public static LinearPerpetualTrafficProfile parse(String value) {
        return valueOf(value.trim().toUpperCase());
    }
}
