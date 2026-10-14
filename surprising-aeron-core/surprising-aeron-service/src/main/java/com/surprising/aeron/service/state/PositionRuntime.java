package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.service.state.instrument.CoreInstrument;

/** Lane-owned position state with primitive in-place updates and immutable publication snapshots. */
public final class PositionRuntime {
    private long userId;
    private int symbolId;
    private int assetId;
    private CoreMarginMode marginMode;
    private CorePositionSide positionSide;
    private CoreInstrument instrument;
    private long signedQuantitySteps;
    private long entryPriceTicks;
    private long entryValueTicks;
    private long realizedPnlUnits;
    private long positionMarginUnits;
    private boolean mutable;
    private boolean publicationSlot;

    private PositionRuntime() { publicationSlot = true; }

    public PositionRuntime(long userId, int symbolId, int assetId, CoreMarginMode marginMode,
                           CorePositionSide positionSide, CoreInstrument instrument,
                           long signedQuantitySteps, long entryPriceTicks, long entryValueTicks,
                           long realizedPnlUnits, long positionMarginUnits) {
        validate(userId, symbolId, assetId, marginMode, positionSide, instrument,
                signedQuantitySteps, entryPriceTicks, entryValueTicks, positionMarginUnits);
        this.userId = userId; this.symbolId = symbolId; this.assetId = assetId;
        this.marginMode = marginMode; this.positionSide = positionSide;
        this.instrument = instrument; this.signedQuantitySteps = signedQuantitySteps;
        this.entryPriceTicks = entryPriceTicks; this.entryValueTicks = entryValueTicks;
        this.realizedPnlUnits = realizedPnlUnits; this.positionMarginUnits = positionMarginUnits;
        this.mutable = true;
    }

    private static void validate(long userId, int symbolId, int assetId, CoreMarginMode marginMode,
                                 CorePositionSide positionSide, CoreInstrument instrument,
                                 long signedQuantitySteps, long entryPriceTicks, long entryValueTicks,
                                 long positionMarginUnits) {
        if (userId <= 0 || symbolId < 0 || assetId < 0 || marginMode == null || positionSide == null
                || instrument == null
                || positionMarginUnits < 0) throw new IllegalArgumentException("invalid runtime position");
        if (signedQuantitySteps == 0) {
            if (entryPriceTicks != 0 || entryValueTicks != 0 || positionMarginUnits != 0)
                throw new IllegalArgumentException("flat runtime position contains open state");
        } else if (entryPriceTicks <= 0 || entryValueTicks <= 0) {
            throw new IllegalArgumentException("open runtime position is incomplete");
        }
    }

    public long userId() { return userId; }
    public int symbolId() { return symbolId; }
    public int assetId() { return assetId; }
    public CoreMarginMode marginMode() { return marginMode; }
    public CorePositionSide positionSide() { return positionSide; }
    public CoreInstrument instrument() { return instrument; }
    public long signedQuantitySteps() { return signedQuantitySteps; }
    public long entryPriceTicks() { return entryPriceTicks; }
    public long entryValueTicks() { return entryValueTicks; }
    public long realizedPnlUnits() { return realizedPnlUnits; }
    public long positionMarginUnits() { return positionMarginUnits; }

    public PositionRuntime snapshot() {
        PositionRuntime copy = new PositionRuntime(userId, symbolId, assetId, marginMode, positionSide,
                instrument, signedQuantitySteps, entryPriceTicks, entryValueTicks,
                realizedPnlUnits, positionMarginUnits);
        copy.mutable = false;
        return copy;
    }

    /** Borrowed after-image owned by the physical change-buffer slot, never a Lane authority. */
    PositionRuntime copyIntoPublicationSlot(PositionRuntime target) {
        if (target == null) target = new PositionRuntime();
        if (!target.publicationSlot) throw new IllegalArgumentException("position target is not a publication slot");
        target.userId = userId;
        target.symbolId = symbolId;
        target.assetId = assetId;
        target.marginMode = marginMode;
        target.positionSide = positionSide;
        target.instrument = instrument;
        target.signedQuantitySteps = signedQuantitySteps;
        target.entryPriceTicks = entryPriceTicks;
        target.entryValueTicks = entryValueTicks;
        target.realizedPnlUnits = realizedPnlUnits;
        target.positionMarginUnits = positionMarginUnits;
        return target;
    }

    void clearPublicationSlot() {
        if (!publicationSlot) throw new IllegalStateException("position is not a publication slot");
        instrument = null;
        marginMode = null;
        positionSide = null;
    }

    PositionRuntime laneValue() {
        return mutable ? this : new PositionRuntime(userId, symbolId, assetId, marginMode, positionSide,
                instrument, signedQuantitySteps, entryPriceTicks, entryValueTicks, realizedPnlUnits,
                positionMarginUnits);
    }

    PositionRuntime publicationValue() { return mutable ? snapshot() : this; }

    /** Owner mirror update; identity fields are fixed and only the owning thread mutates it. */
    void copyStateFrom(PositionRuntime source) {
        if (source == null || userId != source.userId || symbolId != source.symbolId
                || assetId != source.assetId || marginMode != source.marginMode
                || positionSide != source.positionSide || instrument != source.instrument) {
            throw new IllegalArgumentException("position publication identity changed");
        }
        applyInPlace(instrument, source.signedQuantitySteps, source.entryPriceTicks,
                source.entryValueTicks, source.realizedPnlUnits, source.positionMarginUnits);
    }

    void applyInPlace(CoreInstrument instrument, long signedQuantitySteps, long entryPriceTicks,
                      long entryValueTicks, long realizedPnlUnits, long positionMarginUnits) {
        if (this.instrument != instrument) throw new IllegalArgumentException("position instrument mismatch");
        validate(userId, symbolId, assetId, marginMode, positionSide, instrument,
                signedQuantitySteps, entryPriceTicks, entryValueTicks, positionMarginUnits);
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
                && instrument == value.instrument && signedQuantitySteps == value.signedQuantitySteps
                && entryPriceTicks == value.entryPriceTicks && entryValueTicks == value.entryValueTicks
                && realizedPnlUnits == value.realizedPnlUnits && positionMarginUnits == value.positionMarginUnits
                && marginMode == value.marginMode && positionSide == value.positionSide;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(userId, symbolId, assetId, marginMode, positionSide,
                System.identityHashCode(instrument), signedQuantitySteps, entryPriceTicks, entryValueTicks,
                realizedPnlUnits, positionMarginUnits);
    }

    @Override
    public String toString() {
        return "PositionRuntime[userId=" + userId + ", symbolId=" + symbolId + ", assetId=" + assetId
                + ", positionSide=" + positionSide + ", signedQuantitySteps=" + signedQuantitySteps + "]";
    }
}
