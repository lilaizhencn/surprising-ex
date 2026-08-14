package com.surprising.aeron.protocol;

public record CoreOpenInterestView(
        String symbol,
        long longQuantitySteps,
        long shortQuantitySteps) {

    public CoreOpenInterestView {
        if (symbol == null || symbol.isBlank() || longQuantitySteps < 0 || shortQuantitySteps < 0) {
            throw new IllegalArgumentException("invalid core open interest view");
        }
        symbol = symbol.trim().toUpperCase(java.util.Locale.ROOT);
    }
}
