package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CorePositionSide;

/** Lane-owned position state with primitive in-place updates and immutable publication snapshots. */
public final class PositionRuntime {
    private final long userId;
    private final int symbolId;
    private final int assetId;
    private final CoreMarginMode marginMode;
    private final CorePositionSide positionSide;
    private long instrumentChangeId;
    private long signedQuantitySteps;
    private long entryPriceTicks;
    private long entryValueTicks;
    private long realizedPnlUnits;
    private long positionMarginUnits;
    private boolean mutable;

    public PositionRuntime(long userId, int symbolId, int assetId, CoreMarginMode marginMode,
                           CorePositionSide positionSide, long instrumentChangeId,
                           long signedQuantitySteps, long entryPriceTicks, long entryValueTicks,
                           long realizedPnlUnits, long positionMarginUnits) {
        validate(userId, symbolId, assetId, marginMode, positionSide, instrumentChangeId,
                signedQuantitySteps, entryPriceTicks, entryValueTicks, positionMarginUnits);
        this.userId = userId; this.symbolId = symbolId; this.assetId = assetId;
        this.marginMode = marginMode; this.positionSide = positionSide;
        this.instrumentChangeId = instrumentChangeId; this.signedQuantitySteps = signedQuantitySteps;
        this.entryPriceTicks = entryPriceTicks; this.entryValueTicks = entryValueTicks;
        this.realizedPnlUnits = realizedPnlUnits; this.positionMarginUnits = positionMarginUnits;
        this.mutable = true;
    }

    private static void validate(long userId, int symbolId, int assetId, CoreMarginMode marginMode,
                                 CorePositionSide positionSide, long instrumentChangeId,
                                 long signedQuantitySteps, long entryPriceTicks, long entryValueTicks,
                                 long positionMarginUnits) {
        if (userId <= 0 || symbolId < 0 || assetId < 0 || marginMode == null || positionSide == null
                || positionMarginUnits < 0) throw new IllegalArgumentException("invalid runtime position");
        if (signedQuantitySteps == 0) {
            if (instrumentChangeId != 0 || entryPriceTicks != 0 || entryValueTicks != 0 || positionMarginUnits != 0)
                throw new IllegalArgumentException("flat runtime position contains open state");
        } else if (instrumentChangeId <= 0 || entryPriceTicks <= 0 || entryValueTicks <= 0) {
            throw new IllegalArgumentException("open runtime position is incomplete");
        }
    }

    public long userId() { return userId; }
    public int symbolId() { return symbolId; }
    public int assetId() { return assetId; }
    public CoreMarginMode marginMode() { return marginMode; }
    public CorePositionSide positionSide() { return positionSide; }
    public long instrumentChangeId() { return instrumentChangeId; }
    public long signedQuantitySteps() { return signedQuantitySteps; }
    public long entryPriceTicks() { return entryPriceTicks; }
    public long entryValueTicks() { return entryValueTicks; }
    public long realizedPnlUnits() { return realizedPnlUnits; }
    public long positionMarginUnits() { return positionMarginUnits; }

    public PositionRuntime snapshot() {
        PositionRuntime copy = new PositionRuntime(userId, symbolId, assetId, marginMode, positionSide,
                instrumentChangeId, signedQuantitySteps, entryPriceTicks, entryValueTicks,
                realizedPnlUnits, positionMarginUnits);
        copy.mutable = false;
        return copy;
    }

    PositionRuntime laneValue() {
        return mutable ? this : new PositionRuntime(userId, symbolId, assetId, marginMode, positionSide,
                instrumentChangeId, signedQuantitySteps, entryPriceTicks, entryValueTicks, realizedPnlUnits,
                positionMarginUnits);
    }

    PositionRuntime publicationValue() { return mutable ? snapshot() : this; }

    void applyInPlace(long instrumentChangeId, long signedQuantitySteps, long entryPriceTicks,
                      long entryValueTicks, long realizedPnlUnits, long positionMarginUnits) {
        validate(userId, symbolId, assetId, marginMode, positionSide, instrumentChangeId,
                signedQuantitySteps, entryPriceTicks, entryValueTicks, positionMarginUnits);
        this.instrumentChangeId = instrumentChangeId;
        this.signedQuantitySteps = signedQuantitySteps;
        this.entryPriceTicks = entryPriceTicks;
        this.entryValueTicks = entryValueTicks;
        this.realizedPnlUnits = realizedPnlUnits;
        this.positionMarginUnits = positionMarginUnits;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof PositionRuntime value)) return false;
        return userId == value.userId && symbolId == value.symbolId && assetId == value.assetId
                && instrumentChangeId == value.instrumentChangeId && signedQuantitySteps == value.signedQuantitySteps
                && entryPriceTicks == value.entryPriceTicks && entryValueTicks == value.entryValueTicks
                && realizedPnlUnits == value.realizedPnlUnits && positionMarginUnits == value.positionMarginUnits
                && marginMode == value.marginMode && positionSide == value.positionSide;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(userId, symbolId, assetId, marginMode, positionSide,
                instrumentChangeId, signedQuantitySteps, entryPriceTicks, entryValueTicks,
                realizedPnlUnits, positionMarginUnits);
    }

    @Override
    public String toString() {
        return "PositionRuntime[userId=" + userId + ", symbolId=" + symbolId + ", assetId=" + assetId
                + ", positionSide=" + positionSide + ", signedQuantitySteps=" + signedQuantitySteps + "]";
    }
}
