package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CorePositionSide;

public record CoreLiquidationState(
        long liquidationId,
        long userId,
        String symbol,
        CorePositionSide positionSide,
        long instrumentVersion,
        long triggerPriceSequence,
        long closeQuantitySteps,
        long deficitUnits,
        Status status) {

    public CoreLiquidationState {
        symbol = OrderReservation.normalizeSymbol(symbol);
        if (liquidationId <= 0 || userId <= 0 || positionSide == null || instrumentVersion <= 0
                || triggerPriceSequence <= 0 || closeQuantitySteps <= 0 || deficitUnits < 0 || status == null) {
            throw new IllegalArgumentException("invalid liquidation state");
        }
    }

    public CoreLiquidationState(long liquidationId, long userId, String symbol, long instrumentVersion,
                                long triggerPriceSequence, long closeQuantitySteps, long deficitUnits,
                                Status status) {
        this(liquidationId, userId, symbol, CorePositionSide.NET, instrumentVersion, triggerPriceSequence,
                closeQuantitySteps, deficitUnits, status);
    }

    public enum Status {
        PLANNED,
        ORDERED,
        COMPLETED,
        INSURANCE_REQUIRED,
        ADL_REQUIRED
    }

    public CoreLiquidationState withStatus(Status nextStatus) {
        return new CoreLiquidationState(liquidationId, userId, symbol, positionSide, instrumentVersion,
                triggerPriceSequence, closeQuantitySteps, deficitUnits, nextStatus);
    }

    public CoreLiquidationState executed(long uncoveredUnits) {
        return new CoreLiquidationState(liquidationId, userId, symbol, positionSide, instrumentVersion,
                triggerPriceSequence, closeQuantitySteps, uncoveredUnits,
                uncoveredUnits > 0 ? Status.INSURANCE_REQUIRED : Status.COMPLETED);
    }
}
