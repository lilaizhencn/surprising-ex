package com.surprising.aeron.protocol;

public record CoreReservationView(
        long orderId,
        String instrumentId,
        ReservationKind kind,
        String asset,
        long reservedUnits,
        long releasedUnits,
        long consumedUnits,
        long orderQuantitySteps) {
}
