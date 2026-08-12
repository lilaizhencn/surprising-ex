package com.surprising.risk.provider.model;

import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;

public record PositionRiskTarget(
        long userId,
        String symbol,
        MarginMode marginMode,
        PositionSide positionSide,
        long instrumentVersion,
        String accountType,
        String settleAsset) {

    public PositionRiskTarget {
        marginMode = MarginMode.defaultIfNull(marginMode);
        positionSide = PositionSide.defaultIfNull(positionSide);
        accountType = accountType == null || accountType.isBlank()
                ? "USDT_PERPETUAL"
                : accountType.trim().toUpperCase();
    }

    public PositionRiskTarget(long userId,
                              String symbol,
                              MarginMode marginMode,
                              long instrumentVersion,
                              String settleAsset) {
        this(userId, symbol, marginMode, PositionSide.NET, instrumentVersion, "USDT_PERPETUAL", settleAsset);
    }

    public PositionRiskTarget(long userId,
                              String symbol,
                              MarginMode marginMode,
                              PositionSide positionSide,
                              long instrumentVersion,
                              String settleAsset) {
        this(userId, symbol, marginMode, positionSide, instrumentVersion, "USDT_PERPETUAL", settleAsset);
    }

    public PositionRiskTarget(long userId, String symbol, long instrumentVersion, String settleAsset) {
        this(userId, symbol, MarginMode.CROSS, instrumentVersion, settleAsset);
    }

    public RiskGroupKey riskGroupKey() {
        return new RiskGroupKey(userId, accountType, settleAsset);
    }
}
