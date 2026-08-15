package com.surprising.aeron.protocol;

public record ExecuteLiquidationCommand(
        long liquidationId,
        long triggerPriceSequence,
        long executionPriceTicks,
        long liquidationFeeRatePpm) {

    public ExecuteLiquidationCommand {
        if (liquidationId <= 0 || triggerPriceSequence < 0 || executionPriceTicks <= 0
                || liquidationFeeRatePpm < 0 || liquidationFeeRatePpm > 1_000_000) {
            throw new IllegalArgumentException("invalid liquidation execution command");
        }
    }

}
