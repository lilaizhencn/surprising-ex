package com.surprising.liquidation.provider.model;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CorePositionSide;
import java.time.Instant;

public record CoreLiquidationProjection(
        long liquidationId,
        long userId,
        String symbol,
        String asset,
        CoreMarginMode marginMode,
        CorePositionSide positionSide,
        long triggerPriceSequence,
        long signedQuantitySteps,
        long closeQuantitySteps,
        long deficitUnits,
        long executionPriceTicks,
        long liquidationFeeRatePpm,
        long liquidationFeeUnits,
        String status,
        Instant updatedAt) {
}
