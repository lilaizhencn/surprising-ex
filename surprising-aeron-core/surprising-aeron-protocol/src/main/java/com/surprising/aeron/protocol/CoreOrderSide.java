package com.surprising.aeron.protocol;

import java.util.Arrays;

public enum CoreOrderSide {
    BUY(1),
    SELL(2);

    private final int wireCode;

    CoreOrderSide(int wireCode) {
        this.wireCode = wireCode;
    }

    public int wireCode() {
        return wireCode;
    }

    public static CoreOrderSide fromWireCode(int wireCode) {
        return Arrays.stream(values())
                .filter(value -> value.wireCode == wireCode)
                .findFirst()
                .orElseThrow(() -> new ProtocolException("unsupported order side: " + wireCode));
    }
}
