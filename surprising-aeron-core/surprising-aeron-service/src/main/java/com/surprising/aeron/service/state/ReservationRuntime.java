package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.ReservationKind;

public record ReservationRuntime(long orderId, long userId, int symbolId, long instrumentVersion,
                                 ReservationKind kind, int assetId, long totalReservedUnits,
                                 long releasedUnits, long consumedUnits, long orderQuantitySteps) {

    public ReservationRuntime(long orderId, long userId, int assetId, long remainingUnits) {
        this(orderId, userId, 0, 1, ReservationKind.DERIVATIVE_MARGIN, assetId, Math.max(1, remainingUnits),
                0, 0, 1);
    }

    public ReservationRuntime {
        if (orderId <= 0 || userId <= 0 || symbolId < 0 || instrumentVersion <= 0 || kind == null
                || assetId < 0 || totalReservedUnits <= 0 || releasedUnits < 0 || consumedUnits < 0
                || Math.addExact(releasedUnits, consumedUnits) > totalReservedUnits || orderQuantitySteps <= 0) {
            throw new IllegalArgumentException("invalid runtime reservation");
        }
    }

    /** Compatibility accessor used by hot-path code: returns the currently locked amount. */
    public long reservedUnits() {
        return Math.subtractExact(totalReservedUnits, Math.addExact(releasedUnits, consumedUnits));
    }

    public ReservationRuntime withRemainingUnits(long remainingUnits) {
        if (remainingUnits < 0 || remainingUnits > reservedUnits()) {
            throw new IllegalArgumentException("invalid runtime reservation remainder");
        }
        return new ReservationRuntime(orderId, userId, symbolId, instrumentVersion, kind, assetId,
                totalReservedUnits, Math.addExact(releasedUnits, reservedUnits() - remainingUnits),
                consumedUnits, orderQuantitySteps);
    }

    public ReservationRuntime release(long units) {
        if (units < 0 || units > reservedUnits()) throw new IllegalArgumentException("invalid runtime release");
        return new ReservationRuntime(orderId, userId, symbolId, instrumentVersion, kind, assetId,
                totalReservedUnits, Math.addExact(releasedUnits, units), consumedUnits, orderQuantitySteps);
    }

    public ReservationRuntime consume(long units) {
        if (units < 0 || units > reservedUnits()) throw new IllegalArgumentException("invalid runtime consumption");
        return new ReservationRuntime(orderId, userId, symbolId, instrumentVersion, kind, assetId,
                totalReservedUnits, releasedUnits, Math.addExact(consumedUnits, units), orderQuantitySteps);
    }
}
