package com.surprising.adl.api.model;

public record AdlQueuePositionResponse(
        long userId,
        String asset,
        String symbol,
        AdlSide side,
        long signedQuantitySteps,
        long entryPriceTicks,
        long markPriceTicks,
        long notionalUnits,
        long unrealizedProfitUnits,
        long marginUnits,
        long profitRatePpm,
        long effectiveLeveragePpm,
        long priorityScorePpm) {
}
