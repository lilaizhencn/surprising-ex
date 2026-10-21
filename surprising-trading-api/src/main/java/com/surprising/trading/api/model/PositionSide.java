package com.surprising.trading.api.model;

public enum PositionSide {
    NET,
    LONG,
    SHORT;

    public static PositionSide defaultIfNull(PositionSide positionSide) {
        return positionSide == null ? NET : positionSide;
    }

    public boolean isHedgeSide() {
        return this == LONG || this == SHORT;
    }

    public static PositionSide fromNullableDbValue(String value) {
        return value == null || value.isBlank() ? NET : PositionSide.valueOf(value);
    }

    public boolean isOpeningSide(OrderSide orderSide) {
        return (this == NET)
                || (this == LONG && orderSide == OrderSide.BUY)
                || (this == SHORT && orderSide == OrderSide.SELL);
    }

    public boolean isClosingSide(OrderSide orderSide) {
        return (this == LONG && orderSide == OrderSide.SELL)
                || (this == SHORT && orderSide == OrderSide.BUY);
    }
}
