package com.surprising.trading.order.model;

public record SpotReservationRequirement(
        String asset,
        long reservedUnits,
        String rejectReason) {

    public SpotReservationRequirement(String asset, long reservedUnits) {
        this(asset, reservedUnits, null);
    }

    public boolean accepted() {
        return rejectReason == null || rejectReason.isBlank();
    }
}
