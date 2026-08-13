package com.surprising.aeron.protocol;

public record AdjustPositionMarginCommand(
        String symbol,
        CoreMarginMode marginMode,
        CorePositionSide positionSide,
        long amountUnits) {

    public AdjustPositionMarginCommand {
        if (symbol == null || symbol.isBlank() || marginMode == null || positionSide == null || amountUnits == 0) {
            throw new IllegalArgumentException("invalid position margin adjustment");
        }
    }
}
