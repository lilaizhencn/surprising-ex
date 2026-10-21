package com.surprising.trading.api.model;

public enum MarginMode {
    CROSS,
    ISOLATED;

    public static MarginMode defaultIfNull(MarginMode marginMode) {
        return marginMode == null ? CROSS : marginMode;
    }

    public static MarginMode fromNullableDbValue(String value) {
        if (value == null || value.isBlank()) {
            return CROSS;
        }
        return MarginMode.valueOf(value.trim().toUpperCase());
    }
}
