package com.surprising.aeron.service.state.model;

import com.surprising.aeron.service.state.OrderReservation;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CorePositionSide;

public record CorePositionState(
        String instrumentId,
        String marginAsset,
        CoreMarginMode marginMode,
        CorePositionSide positionSide,
        long signedQuantitySteps,
        long entryPriceTicks,
        long entryValueTicks,
        long realizedPnlUnits,
        long positionMarginUnits) {

    public CorePositionState {
        instrumentId = OrderReservation.requireInstrumentId(instrumentId);
        marginAsset = AssetBalance.normalizeAsset(marginAsset);
        if (marginMode == null || positionSide == null || positionMarginUnits < 0) {
            throw new IllegalArgumentException("position margin must not be negative");
        }
        if (signedQuantitySteps == 0) {
            if (entryPriceTicks != 0 || entryValueTicks != 0 || positionMarginUnits != 0) {
                throw new IllegalArgumentException("flat position contains open-position state");
            }
        } else if (entryPriceTicks <= 0 || entryValueTicks <= 0) {
            throw new IllegalArgumentException("open position is incomplete");
        }
    }

    public CorePositionState(String instrumentId, String marginAsset,
                             long signedQuantitySteps, long entryPriceTicks, long entryValueTicks,
                             long realizedPnlUnits, long positionMarginUnits) {
        this(instrumentId, marginAsset, CoreMarginMode.CROSS, CorePositionSide.NET,
                signedQuantitySteps, entryPriceTicks, entryValueTicks, realizedPnlUnits, positionMarginUnits);
    }

    public String key() {
        return positionSide == CorePositionSide.NET ? instrumentId : instrumentId + ':' + positionSide.name();
    }
}
