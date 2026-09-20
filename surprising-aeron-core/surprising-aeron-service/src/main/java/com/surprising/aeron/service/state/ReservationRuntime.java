package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.ReservationKind;

/** Lane-owned reservation; consumption/release mutates primitive counters in place. */
public final class ReservationRuntime {
    private final long orderId;
    private final long userId;
    private final int symbolId;
    private final ReservationKind kind;
    private final int assetId;
    private final long totalReservedUnits;
    private long releasedUnits;
    private long consumedUnits;
    private final long orderQuantitySteps;
    private boolean mutable;

    public ReservationRuntime(long orderId, long userId, int symbolId, ReservationKind kind,
                              int assetId, long totalReservedUnits,
                              long releasedUnits, long consumedUnits, long orderQuantitySteps) {
        if (orderId <= 0 || userId <= 0 || symbolId < 0 || kind == null
                || assetId < 0 || totalReservedUnits <= 0 || releasedUnits < 0 || consumedUnits < 0
                || Math.addExact(releasedUnits, consumedUnits) > totalReservedUnits || orderQuantitySteps <= 0) {
            throw new IllegalArgumentException("invalid runtime reservation");
        }
        this.orderId = orderId; this.userId = userId; this.symbolId = symbolId;
        this.kind = kind; this.assetId = assetId;
        this.totalReservedUnits = totalReservedUnits; this.releasedUnits = releasedUnits;
        this.consumedUnits = consumedUnits; this.orderQuantitySteps = orderQuantitySteps;
        this.mutable = true;
    }

    public long orderId() { return orderId; }
    public long userId() { return userId; }
    public int symbolId() { return symbolId; }
    public ReservationKind kind() { return kind; }
    public int assetId() { return assetId; }
    public long totalReservedUnits() { return totalReservedUnits; }
    public long releasedUnits() { return releasedUnits; }
    public long consumedUnits() { return consumedUnits; }
    public long orderQuantitySteps() { return orderQuantitySteps; }
    public long reservedUnits() { return Math.subtractExact(totalReservedUnits, Math.addExact(releasedUnits, consumedUnits)); }

    public ReservationRuntime snapshot() {
        ReservationRuntime copy = new ReservationRuntime(orderId, userId, symbolId, kind, assetId,
                totalReservedUnits, releasedUnits, consumedUnits, orderQuantitySteps);
        copy.mutable = false;
        return copy;
    }

    ReservationRuntime laneValue() {
        return mutable ? this : new ReservationRuntime(orderId, userId, symbolId, kind, assetId,
                totalReservedUnits, releasedUnits, consumedUnits, orderQuantitySteps);
    }

    ReservationRuntime copyForLane() {
        return new ReservationRuntime(orderId, userId, symbolId, kind, assetId,
                totalReservedUnits, releasedUnits, consumedUnits, orderQuantitySteps);
    }

    ReservationRuntime publicationValue() { return mutable ? snapshot() : this; }

    ReservationRuntime publishedCopy(long released, long consumed) {
        ReservationRuntime copy = new ReservationRuntime(orderId, userId, symbolId, kind, assetId,
                totalReservedUnits, released, consumed, orderQuantitySteps);
        copy.mutable = false;
        return copy;
    }

    void applyPublishedStateInPlace(ReservationRuntime source, long released, long consumed) {
        if (source == null || orderId != source.orderId || userId != source.userId
                || released < 0 || consumed < 0 || Math.addExact(released, consumed) > totalReservedUnits) {
            throw new IllegalStateException("invalid published reservation state");
        }
        releasedUnits = released;
        consumedUnits = consumed;
    }

    void consumeInPlace(long units) {
        if (units < 0 || units > reservedUnits()) throw new IllegalArgumentException("invalid runtime consumption");
        consumedUnits = Math.addExact(consumedUnits, units);
    }

    void releaseInPlace(long units) {
        if (units < 0 || units > reservedUnits()) throw new IllegalArgumentException("invalid runtime release");
        releasedUnits = Math.addExact(releasedUnits, units);
    }

    void setConsumedInPlace(long nextConsumed) {
        if (nextConsumed < consumedUnits || Math.addExact(releasedUnits, nextConsumed) > totalReservedUnits)
            throw new IllegalArgumentException("invalid runtime consumption");
        consumedUnits = nextConsumed;
    }

    public ReservationRuntime withRemainingUnits(long remainingUnits) {
        if (remainingUnits < 0 || remainingUnits > reservedUnits())
            throw new IllegalArgumentException("invalid runtime reservation remainder");
        if (remainingUnits == reservedUnits()) return this;
        return new ReservationRuntime(orderId, userId, symbolId, kind, assetId,
                totalReservedUnits, Math.addExact(releasedUnits, reservedUnits() - remainingUnits),
                consumedUnits, orderQuantitySteps);
    }

    public ReservationRuntime release(long units) {
        if (units < 0 || units > reservedUnits()) throw new IllegalArgumentException("invalid runtime release");
        if (units == 0) return this;
        return new ReservationRuntime(orderId, userId, symbolId, kind, assetId,
                totalReservedUnits, Math.addExact(releasedUnits, units), consumedUnits, orderQuantitySteps);
    }

    public ReservationRuntime consume(long units) {
        if (units < 0 || units > reservedUnits()) throw new IllegalArgumentException("invalid runtime consumption");
        if (units == 0) return this;
        return new ReservationRuntime(orderId, userId, symbolId, kind, assetId,
                totalReservedUnits, releasedUnits, Math.addExact(consumedUnits, units), orderQuantitySteps);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ReservationRuntime value)) return false;
        return orderId == value.orderId && userId == value.userId && symbolId == value.symbolId
                && assetId == value.assetId
                && totalReservedUnits == value.totalReservedUnits && releasedUnits == value.releasedUnits
                && consumedUnits == value.consumedUnits && orderQuantitySteps == value.orderQuantitySteps
                && kind == value.kind;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(orderId, userId, symbolId, kind, assetId,
                totalReservedUnits, releasedUnits, consumedUnits, orderQuantitySteps);
    }

    @Override
    public String toString() {
        return "ReservationRuntime[orderId=" + orderId + ", userId=" + userId + ", assetId=" + assetId
                + ", totalReservedUnits=" + totalReservedUnits + ", releasedUnits=" + releasedUnits
                + ", consumedUnits=" + consumedUnits + "]";
    }
}
