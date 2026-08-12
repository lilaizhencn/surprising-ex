package com.surprising.funding.provider.model;

import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;

public record FundingPaymentCandidate(
        long userId,
        String symbol,
        MarginMode marginMode,
        PositionSide positionSide,
        String asset,
        long signedQuantitySteps,
        long notionalUnits,
        long fundingRatePpm,
        long amountUnits) {

    public FundingPaymentCandidate {
        marginMode = MarginMode.defaultIfNull(marginMode);
        positionSide = PositionSide.defaultIfNull(positionSide);
    }

    public FundingPaymentCandidate(long userId,
                                   String symbol,
                                   MarginMode marginMode,
                                   String asset,
                                   long signedQuantitySteps,
                                   long notionalUnits,
                                   long fundingRatePpm,
                                   long amountUnits) {
        this(userId, symbol, marginMode, PositionSide.NET, asset, signedQuantitySteps, notionalUnits, fundingRatePpm,
                amountUnits);
    }

    public FundingPaymentCandidate(long userId,
                                   String symbol,
                                   String asset,
                                   long signedQuantitySteps,
                                   long notionalUnits,
                                   long fundingRatePpm,
                                   long amountUnits) {
        this(userId, symbol, MarginMode.CROSS, asset, signedQuantitySteps, notionalUnits, fundingRatePpm, amountUnits);
    }
}
