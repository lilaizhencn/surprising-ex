package com.surprising.instrument.api.model;

public enum InstrumentStatus {
    PRE_TRADING,
    TRADING,
    HALT,
    SETTLING,
    CLOSED,
    DRAFT;

    public boolean visible() {
        return this == PRE_TRADING || this == TRADING || this == HALT;
    }
}
