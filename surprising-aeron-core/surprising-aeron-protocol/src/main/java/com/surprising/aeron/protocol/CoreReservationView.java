package com.surprising.aeron.protocol;

public record CoreReservationView(
        long orderId,
        String symbol,
        long instrumentChangeId,
        ReservationKind kind,
        String asset,
        long reservedUnits,
        long releasedUnits,
        long consumedUnits,
        long orderQuantitySteps) {
}
