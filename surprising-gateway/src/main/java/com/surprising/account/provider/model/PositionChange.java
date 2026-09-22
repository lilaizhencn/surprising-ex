package com.surprising.account.provider.model;

public record PositionChange(
        PositionState next,
        long realizedPnlDeltaUnits) {
}
