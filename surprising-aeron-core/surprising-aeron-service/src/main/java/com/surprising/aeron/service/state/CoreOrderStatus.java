package com.surprising.aeron.service.state;

public enum CoreOrderStatus {
    OPEN,
    CANCELED,
    FILLED,
    REJECTED;

    public boolean terminal() {
        return this != OPEN;
    }
}
