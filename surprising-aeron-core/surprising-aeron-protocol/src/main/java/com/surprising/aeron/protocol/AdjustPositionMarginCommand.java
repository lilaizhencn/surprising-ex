package com.surprising.aeron.protocol;

public record AdjustPositionMarginCommand(
        String instrumentId,
        CoreMarginMode marginMode,
        CorePositionSide positionSide,
        long amountUnits) {

    public AdjustPositionMarginCommand {
        if (instrumentId == null || instrumentId.isBlank() || marginMode == null || positionSide == null || amountUnits == 0) {
            throw new IllegalArgumentException("invalid position margin adjustment");
        }
    }
}
