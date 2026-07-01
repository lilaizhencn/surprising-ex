package com.surprising.adl.provider.model;

import com.surprising.adl.api.model.AdlSide;

public record AdlCandidate(
        long userId,
        String asset,
        String symbol,
        AdlSide side,
        long signedQuantitySteps,
        long absQuantitySteps,
        long entryPriceTicks,
        long markPriceTicks,
        long profitTicksPerStep,
        long notionalUnits,
        long unrealizedProfitUnits,
        long marginUnits,
        long profitRatePpm,
        long effectiveLeveragePpm,
        long priorityScorePpm) {
}
