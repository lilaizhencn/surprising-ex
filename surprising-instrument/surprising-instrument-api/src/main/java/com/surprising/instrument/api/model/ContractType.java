package com.surprising.instrument.api.model;

public enum ContractType {
    SPOT,
    LINEAR_PERPETUAL,
    INVERSE_PERPETUAL;

    public boolean isPerpetual() {
        return this == LINEAR_PERPETUAL || this == INVERSE_PERPETUAL;
    }

    public boolean usesPriceQuantityNotional() {
        return this == SPOT || this == LINEAR_PERPETUAL;
    }
}
