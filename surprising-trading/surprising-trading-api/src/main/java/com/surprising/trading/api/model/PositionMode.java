package com.surprising.trading.api.model;

public enum PositionMode {
    ONE_WAY,
    HEDGE;

    public static PositionMode defaultIfNull(PositionMode positionMode) {
        return positionMode == null ? ONE_WAY : positionMode;
    }

    public static PositionMode fromNullableDbValue(String value) {
        return value == null || value.isBlank() ? ONE_WAY : PositionMode.valueOf(value);
    }
}
