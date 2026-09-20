package com.surprising.aeron.service.state;

import com.surprising.aeron.service.state.model.CoreLiquidationState;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.service.state.instrument.CoreInstrument;

public record LiquidationRuntime(long liquidationId, long userId, int symbolId, CoreMarginMode marginMode,
                                 CorePositionSide positionSide, CoreInstrument instrument,
                                 long triggerPriceSequence, long signedQuantitySteps, long closeQuantitySteps,
                                 long deficitUnits, long executionPriceTicks, long liquidationFeeRatePpm,
                                 long liquidationFeeUnits, CoreLiquidationState.Status status,
                                 long nextCancelOrderId) {
    public LiquidationRuntime {
        if (liquidationId <= 0 || userId <= 0 || symbolId < 0 || marginMode == null || positionSide == null
                || instrument == null || triggerPriceSequence <= 0 || signedQuantitySteps == 0
                || closeQuantitySteps <= 0 || closeQuantitySteps > Math.absExact(signedQuantitySteps)
                || deficitUnits < 0 || executionPriceTicks < 0 || liquidationFeeRatePpm < 0
                || liquidationFeeRatePpm > 1_000_000 || liquidationFeeUnits < 0 || status == null
                || nextCancelOrderId < 0) {
            throw new IllegalArgumentException("invalid runtime liquidation");
        }
    }
}
