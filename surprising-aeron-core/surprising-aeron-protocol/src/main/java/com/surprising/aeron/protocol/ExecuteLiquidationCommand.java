package com.surprising.aeron.protocol;

public record ExecuteLiquidationCommand(long liquidationId, long executionPriceTicks) {
    public ExecuteLiquidationCommand {
        if (liquidationId <= 0 || executionPriceTicks <= 0) {
            throw new IllegalArgumentException("invalid liquidation execution command");
        }
    }
}
