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
}
