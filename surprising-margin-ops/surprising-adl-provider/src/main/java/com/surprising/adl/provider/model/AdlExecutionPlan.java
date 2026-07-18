package com.surprising.adl.provider.model;

import com.surprising.adl.api.model.AdlSide;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;

public record AdlExecutionPlan(
        long executionId,
        ProductLine productLine,
        String accountType,
        long deficitUserId,
        long targetUserId,
        String asset,
        String symbol,
        AdlSide targetSide,
        MarginMode targetMarginMode,
        PositionSide targetPositionSide,
        long expectedSignedSteps,
        long closedQuantitySteps,
        long entryPriceTicks,
        long markPriceTicks,
        long requestedDeficitUnits,
        long realizedProfitUnits,
        long coveredUnits,
        long priorityScorePpm,
        String reserveCommandId,
        String targetCommandId,
        String finalizeCommandId) {
}
