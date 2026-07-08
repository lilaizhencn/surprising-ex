package com.surprising.account.provider.model;

public record PositionState(
        long signedQuantitySteps,
        long instrumentVersion,
        long entryPriceTicks,
        long entryValueTicks,
        long realizedPnlUnits) {

    public PositionState(long signedQuantitySteps,
                         long instrumentVersion,
                         long entryPriceTicks,
                         long realizedPnlUnits) {
        this(signedQuantitySteps, instrumentVersion, entryPriceTicks,
                defaultEntryValueTicks(signedQuantitySteps, entryPriceTicks), realizedPnlUnits);
    }

    private static long defaultEntryValueTicks(long signedQuantitySteps, long entryPriceTicks) {
        if (signedQuantitySteps == 0L || entryPriceTicks == 0L) {
            return 0L;
        }
        return Math.multiplyExact(Math.absExact(signedQuantitySteps), entryPriceTicks);
    }
}
