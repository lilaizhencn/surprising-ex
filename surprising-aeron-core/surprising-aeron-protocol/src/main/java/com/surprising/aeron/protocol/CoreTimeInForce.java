package com.surprising.aeron.protocol;

import java.util.Arrays;

public enum CoreTimeInForce {
    GTC(1),
    IOC(2),
    FOK(3),
    GTX(4);

    private final int wireCode;

    CoreTimeInForce(int wireCode) {
        this.wireCode = wireCode;
    }

    public int wireCode() {
        return wireCode;
    }

    public boolean immediate() {
        return this == IOC || this == FOK;
    }

    public static CoreTimeInForce fromWireCode(int wireCode) {
        return Arrays.stream(values()).filter(value -> value.wireCode == wireCode).findFirst()
                .orElseThrow(() -> new ProtocolException("unsupported time in force: " + wireCode));
    }
}
