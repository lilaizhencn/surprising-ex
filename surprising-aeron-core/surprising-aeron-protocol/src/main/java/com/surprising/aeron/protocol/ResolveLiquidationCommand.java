package com.surprising.aeron.protocol;

public record ResolveLiquidationCommand(long liquidationId, Resolution resolution, long coveredUnits) {
    public ResolveLiquidationCommand {
        if (liquidationId <= 0 || resolution == null || coveredUnits < 0) {
            throw new IllegalArgumentException("invalid liquidation resolution command");
        }
    }

    public enum Resolution {
        INSURANCE,
        ADL,
        COMPLETED
    }
}
