package com.surprising.instrument.api.model;

import com.surprising.product.api.ProductLine;

public enum ContractType {
    SPOT,
    LINEAR_PERPETUAL,
    INVERSE_PERPETUAL,
    LINEAR_DELIVERY,
    INVERSE_DELIVERY,
    VANILLA_OPTION;

    public boolean isPerpetual() {
        return this == LINEAR_PERPETUAL || this == INVERSE_PERPETUAL;
    }

    public boolean isDelivery() {
        return this == LINEAR_DELIVERY || this == INVERSE_DELIVERY;
    }

    public boolean isOption() {
        return this == VANILLA_OPTION;
    }

    public boolean isLinear() {
        return this == LINEAR_PERPETUAL || this == LINEAR_DELIVERY || this == VANILLA_OPTION;
    }

    public boolean isInverse() {
        return this == INVERSE_PERPETUAL || this == INVERSE_DELIVERY;
    }

    public ProductLine productLine() {
        return switch (this) {
            case SPOT -> ProductLine.SPOT;
            case LINEAR_PERPETUAL -> ProductLine.LINEAR_PERPETUAL;
            case INVERSE_PERPETUAL -> ProductLine.INVERSE_PERPETUAL;
            case LINEAR_DELIVERY -> ProductLine.LINEAR_DELIVERY;
            case INVERSE_DELIVERY -> ProductLine.INVERSE_DELIVERY;
            case VANILLA_OPTION -> ProductLine.OPTION;
        };
    }

    public boolean usesPriceQuantityNotional() {
        return this == SPOT || this == LINEAR_PERPETUAL || this == LINEAR_DELIVERY || this == VANILLA_OPTION;
    }
}
