package com.surprising.aeron.protocol;

public record CoreLiquidationProgressView(boolean complete, long nextCursorOrderId, int processedOrders) {
    public CoreLiquidationProgressView {
        if (nextCursorOrderId < 0 || processedOrders < 0 || processedOrders > 1024
                || (complete && nextCursorOrderId != 0)) {
            throw new IllegalArgumentException("invalid liquidation progress");
        }
    }
}
