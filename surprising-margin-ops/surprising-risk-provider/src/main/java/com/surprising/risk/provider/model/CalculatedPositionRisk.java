package com.surprising.risk.provider.model;

import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;

public record CalculatedPositionRisk(
        long userId,
        String symbol,
        MarginMode marginMode,
        PositionSide positionSide,
        long instrumentVersion,
        String settleAsset,
        long signedQuantitySteps,
        long entryPriceTicks,
        long markPriceTicks,
        long notionalUnits,
        long unrealizedPnlUnits,
        long maintenanceMarginUnits,
        long positionMarginUnits,
        long positionRevision) {

    public CalculatedPositionRisk {
        marginMode = MarginMode.defaultIfNull(marginMode);
        positionSide = PositionSide.defaultIfNull(positionSide);
        if (positionRevision < 0L) {
            throw new IllegalArgumentException("positionRevision must not be negative");
        }
    }

    public CalculatedPositionRisk(long userId,
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
        this(userId, symbol, marginMode, PositionSide.NET, instrumentVersion, settleAsset, signedQuantitySteps,
                entryPriceTicks, markPriceTicks, notionalUnits, unrealizedPnlUnits, maintenanceMarginUnits,
                positionMarginUnits, 0L);
    }

    /** 兼容持仓版本字段加入前的多空持仓风险构造方式。 */
    public CalculatedPositionRisk(long userId,
                                  String symbol,
                                  MarginMode marginMode,
                                  PositionSide positionSide,
                                  long instrumentVersion,
                                  String settleAsset,
                                  long signedQuantitySteps,
                                  long entryPriceTicks,
                                  long markPriceTicks,
                                  long notionalUnits,
                                  long unrealizedPnlUnits,
                                  long maintenanceMarginUnits,
                                  long positionMarginUnits) {
        this(userId, symbol, marginMode, positionSide, instrumentVersion, settleAsset, signedQuantitySteps,
                entryPriceTicks, markPriceTicks, notionalUnits, unrealizedPnlUnits, maintenanceMarginUnits,
                positionMarginUnits, 0L);
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
