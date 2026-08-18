package com.surprising.adl.api.model;

import com.surprising.trading.api.model.PositionSide;

public record AdlQueuePositionResponse(
        long userId,
        String asset,
        String symbol,
        PositionSide positionSide,
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

    public AdlQueuePositionResponse {
        positionSide = PositionSide.defaultIfNull(positionSide);
    }

    public AdlQueuePositionResponse(long userId,
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
        this(userId, asset, symbol, PositionSide.NET, side, signedQuantitySteps, entryPriceTicks, markPriceTicks,
                notionalUnits, unrealizedProfitUnits, marginUnits, profitRatePpm, effectiveLeveragePpm,
                priorityScorePpm);
    }
}
