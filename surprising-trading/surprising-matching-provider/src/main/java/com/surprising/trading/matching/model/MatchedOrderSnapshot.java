package com.surprising.trading.matching.model;

import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;

public record MatchedOrderSnapshot(
        long instrumentVersion,
        MarginMode marginMode,
        PositionSide positionSide,
        long makerFeeRatePpm,
        long takerFeeRatePpm,
        long quantitySteps,
        long remainingQuantitySteps) {

    public MatchedOrderSnapshot {
        marginMode = MarginMode.defaultIfNull(marginMode);
        positionSide = PositionSide.defaultIfNull(positionSide);
        if (quantitySteps <= 0 || remainingQuantitySteps <= 0 || remainingQuantitySteps > quantitySteps) {
            throw new IllegalArgumentException("invalid matched order quantity snapshot");
        }
    }
}
