package com.surprising.aeron.protocol;

import java.util.Arrays;

public enum CorePositionMode {
    ONE_WAY(0),
    HEDGE(1);

    private final int wireCode;

    CorePositionMode(int wireCode) {
        this.wireCode = wireCode;
    }

    public int wireCode() {
        return wireCode;
    }

    public static CorePositionMode fromWireCode(int wireCode) {
        return Arrays.stream(values()).filter(value -> value.wireCode == wireCode).findFirst()
                .orElseThrow(() -> new ProtocolException("unsupported position mode: " + wireCode));
    }
}
