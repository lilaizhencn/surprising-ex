package com.surprising.aeron.service.state;

public record ReservationRuntime(long orderId, long userId, int assetId, long reservedUnits) {
    public ReservationRuntime {
        if (orderId <= 0 || userId <= 0 || assetId < 0 || reservedUnits < 0) {
            throw new IllegalArgumentException("invalid runtime reservation");
        }
    }
}
