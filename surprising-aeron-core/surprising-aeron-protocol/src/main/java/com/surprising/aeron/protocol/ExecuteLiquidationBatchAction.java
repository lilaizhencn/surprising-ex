package com.surprising.aeron.protocol;

import java.nio.charset.StandardCharsets;

public record ExecuteLiquidationBatchAction(
        long liquidationId,
        long userId,
        String symbol,
        long instrumentVersion,
        long triggerPriceSequence,
        long executionPriceTicks,
        long cursorOrderId) {

    private static final int MAX_SYMBOL_BYTES = 64;

    public ExecuteLiquidationBatchAction {
        if (liquidationId <= 0 || userId <= 0 || symbol == null || symbol.isBlank()
                || symbol.getBytes(StandardCharsets.UTF_8).length > MAX_SYMBOL_BYTES
                || instrumentVersion <= 0 || triggerPriceSequence <= 0 || executionPriceTicks <= 0
                || cursorOrderId < 0) {
            throw new IllegalArgumentException("invalid liquidation batch action");
        }
    }
}
