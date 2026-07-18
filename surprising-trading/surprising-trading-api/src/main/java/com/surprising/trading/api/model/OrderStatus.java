package com.surprising.trading.api.model;

public enum OrderStatus {
    PENDING_RESERVE,
    ACCEPTED,
    REJECTED,
    CANCEL_REQUESTED,
    CANCELED,
    PARTIALLY_FILLED,
    FILLED
}
