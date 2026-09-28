package com.surprising.aeron.protocol;

public record CoreOpenInterestView(
        String instrumentId,
        long longQuantitySteps,
        long shortQuantitySteps) {

    public CoreOpenInterestView {
        if (instrumentId == null || instrumentId.isBlank() || longQuantitySteps < 0 || shortQuantitySteps < 0) {
            throw new IllegalArgumentException("invalid core open interest view");
        }
        instrumentId = instrumentId.trim().toUpperCase(java.util.Locale.ROOT);
    }
}
