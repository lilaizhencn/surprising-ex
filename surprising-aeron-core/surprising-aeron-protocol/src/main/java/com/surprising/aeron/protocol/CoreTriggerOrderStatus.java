package com.surprising.aeron.protocol;

public enum CoreTriggerOrderStatus {
    PENDING,
    TRIGGERING,
    TRIGGERED,
    TRIGGER_FAILED,
    CANCELED,
    EXPIRED;

    public boolean open() {
        return this == PENDING || this == TRIGGERING;
    }
}
