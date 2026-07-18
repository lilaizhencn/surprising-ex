package com.surprising.account.api.model;

import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;

/**
 * Applies one user's immutable funding payment inside the account single-writer boundary.
 */
public record FundingSettlementAccountCommand(
        long settlementId,
        long paymentId,
        String symbol,
        MarginMode marginMode,
        PositionSide positionSide,
        String asset,
        long signedQuantitySteps,
        long notionalUnits,
        long fundingRatePpm,
        long amountUnits) {

    public FundingSettlementAccountCommand {
        if (settlementId <= 0 || paymentId <= 0) {
            throw new IllegalArgumentException("settlementId and paymentId must be positive");
        }
        symbol = requireCode(symbol, "symbol", 64);
        marginMode = MarginMode.defaultIfNull(marginMode);
        positionSide = PositionSide.defaultIfNull(positionSide);
        asset = requireCode(asset, "asset", 20);
        if (signedQuantitySteps == 0) {
            throw new IllegalArgumentException("signedQuantitySteps must be non-zero");
        }
        if (notionalUnits < 0) {
            throw new IllegalArgumentException("notionalUnits must be non-negative");
        }
        if (amountUnits == 0) {
            throw new IllegalArgumentException("amountUnits must be non-zero");
        }
    }

    private static String requireCode(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        String normalized = value.trim().toUpperCase();
        if (normalized.length() > maxLength || !normalized.matches("[A-Z0-9][A-Z0-9_-]*")) {
            throw new IllegalArgumentException("invalid " + field + ": " + value);
        }
        return normalized;
    }
}
