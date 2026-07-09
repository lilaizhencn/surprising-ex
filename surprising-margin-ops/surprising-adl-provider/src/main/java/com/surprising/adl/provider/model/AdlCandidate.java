package com.surprising.adl.provider.model;

import com.surprising.adl.api.model.AdlSide;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;

public record AdlCandidate(
        long userId,
        String asset,
        String symbol,
        MarginMode marginMode,
        PositionSide positionSide,
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

    public AdlCandidate {
        marginMode = MarginMode.defaultIfNull(marginMode);
        positionSide = PositionSide.defaultIfNull(positionSide);
    }

    public AdlCandidate(long userId,
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
        this(userId, asset, symbol, MarginMode.CROSS, PositionSide.NET, side, signedQuantitySteps, absQuantitySteps,
                entryPriceTicks, markPriceTicks, profitTicksPerStep, notionalUnits, unrealizedProfitUnits, marginUnits,
                profitRatePpm, effectiveLeveragePpm, priorityScorePpm);
    }
}
