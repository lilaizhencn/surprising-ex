package com.surprising.account.api.model;

import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.time.Instant;

/** User-scoped delivery or option-expiry settlement command. */
public record ExpiringPositionSettlementAccountCommand(
        String symbol,
        long instrumentVersion,
        MarginMode marginMode,
        PositionSide positionSide,
        long settlementPriceTicks,
        String referenceType,
        String reason,
        Instant eventTime) {

    public ExpiringPositionSettlementAccountCommand {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol is required");
        }
        symbol = symbol.trim().toUpperCase();
        if (!symbol.matches("[A-Z0-9][A-Z0-9_-]{1,63}")) {
            throw new IllegalArgumentException("invalid symbol: " + symbol);
        }
        if (instrumentVersion <= 0 || settlementPriceTicks < 0) {
            throw new IllegalArgumentException("invalid expiring settlement price identity");
        }
        marginMode = MarginMode.defaultIfNull(marginMode);
        positionSide = PositionSide.defaultIfNull(positionSide);
        if (referenceType == null || referenceType.isBlank() || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("referenceType and reason are required");
        }
        referenceType = referenceType.trim().toUpperCase();
        reason = reason.trim();
        eventTime = eventTime == null ? Instant.now() : eventTime;
    }
}
