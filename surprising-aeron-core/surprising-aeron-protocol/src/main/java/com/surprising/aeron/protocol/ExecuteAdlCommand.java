package com.surprising.aeron.protocol;

public record ExecuteAdlCommand(
        long liquidationId,
        long targetUserId,
        String instrumentId,
        CoreMarginMode marginMode,
        CorePositionSide positionSide,
        long expectedSignedQuantitySteps,
        long expectedEntryPriceTicks,
        long markPriceSequence,
        long closeQuantitySteps,
        long coveredUnits) {

    public ExecuteAdlCommand {
        if (liquidationId <= 0 || targetUserId <= 0 || instrumentId == null || instrumentId.isBlank()
                || marginMode == null || positionSide == null || expectedSignedQuantitySteps == 0
                || expectedEntryPriceTicks <= 0 || markPriceSequence <= 0 || closeQuantitySteps <= 0
                || closeQuantitySteps > Math.absExact(expectedSignedQuantitySteps) || coveredUnits <= 0) {
            throw new IllegalArgumentException("invalid ADL command");
        }
        instrumentId = instrumentId.trim().toUpperCase();
    }
}
