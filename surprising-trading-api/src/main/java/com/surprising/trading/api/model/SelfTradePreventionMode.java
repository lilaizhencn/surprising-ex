package com.surprising.trading.api.model;

/**
 * 防自成交保护模式 (Self-Trade Prevention Mode)
 */
public enum SelfTradePreventionMode {
    NONE,
    EXPIRE_MAKER,
    EXPIRE_TAKER,
    EXPIRE_BOTH;

    public static SelfTradePreventionMode defaultIfNull(SelfTradePreventionMode mode) {
        return mode != null ? mode : NONE;
    }
}
