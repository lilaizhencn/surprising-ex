package com.surprising.aeron.service.state;

public record CoreLiquidationState(
        long liquidationId,
        long userId,
        String symbol,
        long instrumentVersion,
        long triggerPriceSequence,
        long closeQuantitySteps,
        long deficitUnits,
        Status status) {

    public CoreLiquidationState {
        symbol = OrderReservation.normalizeSymbol(symbol);
        if (liquidationId <= 0 || userId <= 0 || instrumentVersion <= 0
                || triggerPriceSequence <= 0 || closeQuantitySteps <= 0 || deficitUnits < 0 || status == null) {
            throw new IllegalArgumentException("invalid liquidation state");
        }
    }

    public enum Status {
        PLANNED,
        ORDERED,
        COMPLETED,
        INSURANCE_REQUIRED,
        ADL_REQUIRED
    }

    public CoreLiquidationState withStatus(Status nextStatus) {
        return new CoreLiquidationState(liquidationId, userId, symbol, instrumentVersion,
                triggerPriceSequence, closeQuantitySteps, deficitUnits, nextStatus);
    }

    public CoreLiquidationState executed(long uncoveredUnits) {
        return new CoreLiquidationState(liquidationId, userId, symbol, instrumentVersion,
                triggerPriceSequence, closeQuantitySteps, uncoveredUnits,
                uncoveredUnits > 0 ? Status.INSURANCE_REQUIRED : Status.COMPLETED);
    }
}
