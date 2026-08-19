package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CorePositionSide;

public record PositionRuntime(long userId, int symbolId, int assetId, CoreMarginMode marginMode,
                              CorePositionSide positionSide, long instrumentVersion,
                              long signedQuantitySteps, long entryPriceTicks, long entryValueTicks,
                              long realizedPnlUnits, long positionMarginUnits) {
    public PositionRuntime {
        if (userId <= 0 || symbolId < 0 || assetId < 0 || marginMode == null || positionSide == null
                || positionMarginUnits < 0) {
            throw new IllegalArgumentException("invalid runtime position");
        }
        if (signedQuantitySteps == 0) {
            if (instrumentVersion != 0 || entryPriceTicks != 0 || entryValueTicks != 0
                    || positionMarginUnits != 0) {
                throw new IllegalArgumentException("flat runtime position contains open state");
            }
        } else if (instrumentVersion <= 0 || entryPriceTicks <= 0 || entryValueTicks <= 0) {
            throw new IllegalArgumentException("open runtime position is incomplete");
        }
    }
}
