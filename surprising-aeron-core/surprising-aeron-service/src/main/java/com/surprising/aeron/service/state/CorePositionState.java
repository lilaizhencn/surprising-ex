package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CorePositionSide;

public record CorePositionState(
        String symbol,
        String marginAsset,
        CoreMarginMode marginMode,
        CorePositionSide positionSide,
        long instrumentVersion,
        long signedQuantitySteps,
        long entryPriceTicks,
        long entryValueTicks,
        long realizedPnlUnits,
        long positionMarginUnits) {

    public CorePositionState {
        symbol = OrderReservation.normalizeSymbol(symbol);
        marginAsset = AssetBalance.normalizeAsset(marginAsset);
        if (marginMode == null || positionSide == null || positionMarginUnits < 0) {
            throw new IllegalArgumentException("position margin must not be negative");
        }
        if (signedQuantitySteps == 0) {
            if (instrumentVersion != 0 || entryPriceTicks != 0 || entryValueTicks != 0 || positionMarginUnits != 0) {
                throw new IllegalArgumentException("flat position contains open-position state");
            }
        } else if (instrumentVersion <= 0 || entryPriceTicks <= 0 || entryValueTicks <= 0) {
            throw new IllegalArgumentException("open position is incomplete");
        }
    }

    public CorePositionState(String symbol, String marginAsset, long instrumentVersion,
                             long signedQuantitySteps, long entryPriceTicks, long entryValueTicks,
                             long realizedPnlUnits, long positionMarginUnits) {
        this(symbol, marginAsset, CoreMarginMode.CROSS, CorePositionSide.NET, instrumentVersion,
                signedQuantitySteps, entryPriceTicks, entryValueTicks, realizedPnlUnits, positionMarginUnits);
    }

    public String key() {
        return positionSide == CorePositionSide.NET ? symbol : symbol + ':' + positionSide.name();
    }
}
