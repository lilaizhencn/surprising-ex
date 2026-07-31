package com.surprising.funding.provider.model;

public record FundingPaymentCursor(long userId, String marginMode, String positionSide) {

    public FundingPaymentCursor {
        marginMode = marginMode == null ? "" : marginMode;
        positionSide = positionSide == null ? "" : positionSide;
    }

    public static FundingPaymentCursor from(FundingPaymentCandidate payment) {
        return new FundingPaymentCursor(payment.userId(), payment.marginMode().name(),
                payment.positionSide().name());
    }
}
