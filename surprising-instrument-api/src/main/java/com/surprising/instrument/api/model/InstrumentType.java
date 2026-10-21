package com.surprising.instrument.api.model;

public enum InstrumentType {
    SPOT,
    PERPETUAL,
    DELIVERY,
    OPTION;

    public boolean isDerivative() {
        return this != SPOT;
    }
}
