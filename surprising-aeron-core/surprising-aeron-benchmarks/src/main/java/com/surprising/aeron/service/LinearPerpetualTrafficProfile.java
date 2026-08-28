package com.surprising.aeron.service;

enum LinearPerpetualTrafficProfile {
    UNIFORM,
    PARETO_80_20,
    SINGLE_HOT,
    MOSTLY_IDLE,
    MARK_PRICE_STORM;

    static LinearPerpetualTrafficProfile parse(String value) {
        return valueOf(value.trim().toUpperCase());
    }
}
