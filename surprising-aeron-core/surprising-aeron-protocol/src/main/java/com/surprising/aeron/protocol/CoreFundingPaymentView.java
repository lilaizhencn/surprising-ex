package com.surprising.aeron.protocol;

public record CoreFundingPaymentView(
        long settlementId,
        long userId,
        String symbol,
        CoreMarginMode marginMode,
        CorePositionSide positionSide,
        String asset,
        long signedQuantitySteps,
        long notionalUnits,
        long fundingRatePpm,
        long amountUnits) {

    public CoreFundingPaymentView {
        if (settlementId <= 0 || userId <= 0 || symbol == null || symbol.isBlank()
                || marginMode == null || positionSide == null || asset == null || asset.isBlank()
                || signedQuantitySteps == 0 || notionalUnits < 0 || fundingRatePpm == 0 || amountUnits == 0) {
            throw new IllegalArgumentException("invalid core funding payment");
        }
    }
}
