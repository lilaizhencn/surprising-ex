package com.surprising.risk.provider.model;

import com.surprising.trading.api.model.MarginMode;

public record PositionRiskTarget(
        long userId,
        String symbol,
        MarginMode marginMode,
        long instrumentVersion,
        String settleAsset) {

    public PositionRiskTarget {
        marginMode = MarginMode.defaultIfNull(marginMode);
    }

    public PositionRiskTarget(long userId, String symbol, long instrumentVersion, String settleAsset) {
        this(userId, symbol, MarginMode.CROSS, instrumentVersion, settleAsset);
    }

    public RiskGroupKey riskGroupKey() {
        return new RiskGroupKey(userId, settleAsset);
    }
}
