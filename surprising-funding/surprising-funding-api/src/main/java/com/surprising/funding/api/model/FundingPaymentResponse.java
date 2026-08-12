package com.surprising.funding.api.model;

import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.time.Instant;

public record FundingPaymentResponse(
        long paymentId,
        long settlementId,
        long userId,
        String symbol,
        MarginMode marginMode,
        PositionSide positionSide,
        String asset,
        long signedQuantitySteps,
        long notionalUnits,
        long fundingRatePpm,
        long amountUnits,
        Instant createdAt) {

    public FundingPaymentResponse {
        marginMode = MarginMode.defaultIfNull(marginMode);
        positionSide = PositionSide.defaultIfNull(positionSide);
    }

    public FundingPaymentResponse(long paymentId,
                                  long settlementId,
                                  long userId,
                                  String symbol,
                                  MarginMode marginMode,
                                  String asset,
                                  long signedQuantitySteps,
                                  long notionalUnits,
                                  long fundingRatePpm,
                                  long amountUnits,
                                  Instant createdAt) {
        this(paymentId, settlementId, userId, symbol, marginMode, PositionSide.NET, asset, signedQuantitySteps,
                notionalUnits, fundingRatePpm, amountUnits, createdAt);
    }

    public FundingPaymentResponse(long paymentId,
                                  long settlementId,
                                  long userId,
                                  String symbol,
                                  String asset,
                                  long signedQuantitySteps,
                                  long notionalUnits,
                                  long fundingRatePpm,
                                  long amountUnits,
                                  Instant createdAt) {
        this(paymentId, settlementId, userId, symbol, MarginMode.CROSS, PositionSide.NET, asset, signedQuantitySteps, notionalUnits,
                fundingRatePpm, amountUnits, createdAt);
    }
}
