package com.surprising.aeron.protocol;

public record ExecuteLiquidationCommand(
        long liquidationId,
        long triggerPriceSequence,
        long executionPriceTicks,
        long liquidationFeeRatePpm,
        long cursorOrderId,
        int maxOrders) {

    public static final int DEFAULT_MAX_ORDERS = 1_024;

    public ExecuteLiquidationCommand(long liquidationId, long triggerPriceSequence,
                                     long executionPriceTicks, long liquidationFeeRatePpm) {
        this(liquidationId, triggerPriceSequence, executionPriceTicks, liquidationFeeRatePpm,
                0, DEFAULT_MAX_ORDERS);
    }

    public ExecuteLiquidationCommand {
        if (liquidationId <= 0 || triggerPriceSequence < 0 || executionPriceTicks <= 0
                || liquidationFeeRatePpm < 0 || liquidationFeeRatePpm > 1_000_000
                || cursorOrderId < 0 || maxOrders < 1 || maxOrders > DEFAULT_MAX_ORDERS) {
            throw new IllegalArgumentException("invalid liquidation execution command");
        }
    }

}
