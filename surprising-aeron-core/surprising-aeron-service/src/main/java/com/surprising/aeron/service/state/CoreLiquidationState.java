package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreMarginMode;

public record CoreLiquidationState(
        long liquidationId,
        long userId,
        String symbol,
        CoreMarginMode marginMode,
        CorePositionSide positionSide,
        long instrumentVersion,
        long triggerPriceSequence,
        long signedQuantitySteps,
        long closeQuantitySteps,
        long deficitUnits,
        long executionPriceTicks,
        long liquidationFeeRatePpm,
        long liquidationFeeUnits,
        Status status,
        long nextCancelOrderId) {

    public CoreLiquidationState {
        symbol = OrderReservation.normalizeSymbol(symbol);
        if (liquidationId <= 0 || userId <= 0 || marginMode == null || positionSide == null || instrumentVersion <= 0
                || triggerPriceSequence <= 0 || signedQuantitySteps == 0 || closeQuantitySteps <= 0
                || closeQuantitySteps > Math.absExact(signedQuantitySteps) || deficitUnits < 0
                || executionPriceTicks < 0 || liquidationFeeRatePpm < 0
                || liquidationFeeRatePpm > 1_000_000 || liquidationFeeUnits < 0 || status == null
                || nextCancelOrderId < 0
                || (status == Status.PLANNED || status == Status.CANCELED)
                && (executionPriceTicks != 0 || liquidationFeeRatePpm != 0 || liquidationFeeUnits != 0)
                || (status != Status.ORDERED && nextCancelOrderId != 0)) {
            throw new IllegalArgumentException("invalid liquidation state");
        }
    }

    public CoreLiquidationState(long liquidationId, long userId, String symbol, CoreMarginMode marginMode,
                                CorePositionSide positionSide, long instrumentVersion, long triggerPriceSequence,
                                long signedQuantitySteps, long closeQuantitySteps, long deficitUnits,
                                long executionPriceTicks, long liquidationFeeRatePpm, long liquidationFeeUnits,
                                Status status) {
        this(liquidationId, userId, symbol, marginMode, positionSide, instrumentVersion, triggerPriceSequence,
                signedQuantitySteps, closeQuantitySteps, deficitUnits, executionPriceTicks, liquidationFeeRatePpm,
                liquidationFeeUnits, status, 0);
    }

    public CoreLiquidationState(long liquidationId, long userId, String symbol, CorePositionSide positionSide,
                                long instrumentVersion, long triggerPriceSequence, long signedQuantitySteps,
                                long closeQuantitySteps, long deficitUnits, Status status) {
        this(liquidationId, userId, symbol, CoreMarginMode.CROSS, positionSide, instrumentVersion,
                triggerPriceSequence, signedQuantitySteps, closeQuantitySteps, deficitUnits, 0, 0, 0, status, 0);
    }

    public CoreLiquidationState(long liquidationId, long userId, String symbol, long instrumentVersion,
                                long triggerPriceSequence, long closeQuantitySteps, long deficitUnits,
                                Status status) {
        this(liquidationId, userId, symbol, CoreMarginMode.CROSS, CorePositionSide.NET, instrumentVersion,
                triggerPriceSequence, closeQuantitySteps, closeQuantitySteps, deficitUnits, 0, 0, 0, status, 0);
    }

    public CoreLiquidationState(long liquidationId, long userId, String symbol, CorePositionSide positionSide,
                                long instrumentVersion, long triggerPriceSequence, long closeQuantitySteps,
                                long deficitUnits, Status status) {
        this(liquidationId, userId, symbol, CoreMarginMode.CROSS, positionSide, instrumentVersion, triggerPriceSequence,
                positionSide == CorePositionSide.SHORT ? Math.negateExact(closeQuantitySteps) : closeQuantitySteps,
                closeQuantitySteps, deficitUnits, 0, 0, 0, status, 0);
    }

    public enum Status {
        PLANNED,
        ORDERED,
        COMPLETED,
        INSURANCE_REQUIRED,
        ADL_REQUIRED,
        CANCELED
    }

    public boolean terminal() {
        return status == Status.COMPLETED || status == Status.CANCELED;
    }

    public CoreLiquidationState withStatus(Status nextStatus) {
        return new CoreLiquidationState(liquidationId, userId, symbol, marginMode, positionSide, instrumentVersion,
                triggerPriceSequence, signedQuantitySteps, closeQuantitySteps, deficitUnits,
                executionPriceTicks, liquidationFeeRatePpm, liquidationFeeUnits, nextStatus,
                nextStatus == Status.ORDERED ? nextCancelOrderId : 0);
    }

    public CoreLiquidationState refreshed(CoreMarginMode nextMarginMode, long nextPriceSequence,
                                          long nextSignedQuantitySteps) {
        if (status != Status.PLANNED) throw new IllegalStateException("only planned liquidation can refresh");
        return new CoreLiquidationState(liquidationId, userId, symbol, nextMarginMode, positionSide,
                instrumentVersion, nextPriceSequence, nextSignedQuantitySteps,
                Math.absExact(nextSignedQuantitySteps), 0, 0, 0, 0, Status.PLANNED);
    }

    public CoreLiquidationState canceled() {
        if (status != Status.PLANNED) throw new IllegalStateException("only planned liquidation can cancel");
        return new CoreLiquidationState(liquidationId, userId, symbol, marginMode, positionSide,
                instrumentVersion, triggerPriceSequence, signedQuantitySteps, closeQuantitySteps,
                0, 0, 0, 0, Status.CANCELED, 0);
    }

    public CoreLiquidationState ordered(long nextOrderId) {
        if (nextOrderId <= 0) throw new IllegalArgumentException("next cancellation order id must be positive");
        return new CoreLiquidationState(liquidationId, userId, symbol, marginMode, positionSide,
                instrumentVersion, triggerPriceSequence, signedQuantitySteps, closeQuantitySteps,
                deficitUnits, executionPriceTicks, liquidationFeeRatePpm, liquidationFeeUnits,
                Status.ORDERED, nextOrderId);
    }

    public CoreLiquidationState executed(long uncoveredUnits, long priceTicks, long feeRatePpm, long feeUnits) {
        return new CoreLiquidationState(liquidationId, userId, symbol, marginMode, positionSide, instrumentVersion,
                triggerPriceSequence, signedQuantitySteps, closeQuantitySteps, uncoveredUnits,
                priceTicks, feeRatePpm, feeUnits,
                uncoveredUnits > 0 ? Status.INSURANCE_REQUIRED : Status.COMPLETED, 0);
    }

    public CoreLiquidationState covered(long coveredUnits, Status nextStatus) {
        if (coveredUnits <= 0 || coveredUnits > deficitUnits) {
            throw new IllegalArgumentException("invalid liquidation coverage");
        }
        return new CoreLiquidationState(liquidationId, userId, symbol, marginMode, positionSide, instrumentVersion,
                triggerPriceSequence, signedQuantitySteps, closeQuantitySteps,
                Math.subtractExact(deficitUnits, coveredUnits), executionPriceTicks,
                liquidationFeeRatePpm, liquidationFeeUnits, nextStatus, 0);
    }
}
