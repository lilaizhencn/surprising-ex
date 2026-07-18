package com.surprising.account.api.model;

import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;

/** Immutable target-side snapshot for a conditional ADL settlement. */
public record AdlTargetSettlementAccountCommand(
        long executionId,
        long deficitUserId,
        String asset,
        String symbol,
        MarginMode marginMode,
        PositionSide positionSide,
        long expectedSignedQuantitySteps,
        long closeQuantitySteps,
        long expectedEntryPriceTicks,
        long markPriceTicks,
        long expectedRealizedProfitUnits,
        long coveredUnits) {

    public AdlTargetSettlementAccountCommand {
        if (executionId <= 0 || deficitUserId <= 0) {
            throw new IllegalArgumentException("executionId and deficitUserId must be positive");
        }
        asset = requireCode(asset, "asset", 20);
        symbol = requireCode(symbol, "symbol", 64);
        marginMode = MarginMode.defaultIfNull(marginMode);
        positionSide = PositionSide.defaultIfNull(positionSide);
        if (expectedSignedQuantitySteps == 0 || closeQuantitySteps <= 0
                || closeQuantitySteps > Math.absExact(expectedSignedQuantitySteps)) {
            throw new IllegalArgumentException("invalid ADL position quantities");
        }
        if (expectedEntryPriceTicks <= 0 || markPriceTicks <= 0
                || expectedRealizedProfitUnits <= 0 || coveredUnits <= 0
                || coveredUnits > expectedRealizedProfitUnits) {
            throw new IllegalArgumentException("invalid ADL settlement amounts");
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
