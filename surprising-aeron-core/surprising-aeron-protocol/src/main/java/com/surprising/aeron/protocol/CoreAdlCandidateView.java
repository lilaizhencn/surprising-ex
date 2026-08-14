package com.surprising.aeron.protocol;

public record CoreAdlCandidateView(
        long userId,
        String symbol,
        String asset,
        CoreMarginMode marginMode,
        CorePositionSide positionSide,
        long signedQuantitySteps,
        long entryPriceTicks,
        long markPriceTicks,
        long markPriceSequence,
        long notionalUnits,
        long unrealizedProfitUnits,
        long marginUnits,
        long profitRatePpm,
        long effectiveLeveragePpm,
        long priorityScorePpm) {
}
