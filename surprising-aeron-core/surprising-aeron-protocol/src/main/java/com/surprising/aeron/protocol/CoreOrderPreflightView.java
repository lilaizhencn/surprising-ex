package com.surprising.aeron.protocol;

public record CoreOrderPreflightView(String reservationAsset, long reservedUnits) {
    public CoreOrderPreflightView {
        if (reservationAsset == null || reservationAsset.isBlank() || reservedUnits <= 0) {
            throw new IllegalArgumentException("invalid order preflight result");
        }
    }
}
