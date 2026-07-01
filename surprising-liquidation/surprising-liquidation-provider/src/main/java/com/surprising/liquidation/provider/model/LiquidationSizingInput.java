package com.surprising.liquidation.provider.model;

public record LiquidationSizingInput(
        long positionAbsSteps,
        long availableCloseSteps,
        long notionalUnits,
        long notionalPerStepUnits,
        long bracketFloorNotionalUnits) {
}
