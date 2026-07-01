package com.surprising.risk.provider.model;

import com.surprising.trading.api.model.MarginMode;

public record CalculatedPositionRisk(
        long userId,
        String symbol,
        MarginMode marginMode,
        long instrumentVersion,
        String settleAsset,
        long signedQuantitySteps,
        long entryPriceTicks,
        long markPriceTicks,
        long notionalUnits,
        long unrealizedPnlUnits,
        long maintenanceMarginUnits,
        long positionMarginUnits) {

    public CalculatedPositionRisk {
        marginMode = MarginMode.defaultIfNull(marginMode);
    }

    public CalculatedPositionRisk(long userId,
                                  String symbol,
                                  long instrumentVersion,
                                  String settleAsset,
                                  long signedQuantitySteps,
                                  long entryPriceTicks,
                                  long markPriceTicks,
                                  long notionalUnits,
                                  long unrealizedPnlUnits,
                                  long maintenanceMarginUnits) {
        this(userId, symbol, MarginMode.CROSS, instrumentVersion, settleAsset, signedQuantitySteps,
                entryPriceTicks, markPriceTicks, notionalUnits, unrealizedPnlUnits, maintenanceMarginUnits, 0L);
    }
}
