package com.surprising.aeron.protocol;

public record AdjustPositionMarginCommand(
        String instrumentId,
        CorePositionSide positionSide,
        long amountUnits) {

    public AdjustPositionMarginCommand {
        if (instrumentId == null || instrumentId.isBlank() || positionSide == null || amountUnits == 0) {
            throw new IllegalArgumentException("invalid position margin adjustment");
        }
    }
}
