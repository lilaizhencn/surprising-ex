package com.surprising.aeron.protocol;

import java.util.Arrays;

public enum CorePositionSide {
    NET(0),
    LONG(1),
    SHORT(2);

    private final int wireCode;

    CorePositionSide(int wireCode) {
        this.wireCode = wireCode;
    }

    public int wireCode() {
        return wireCode;
    }

    public boolean hedgeSide() {
        return this != NET;
    }

    public static CorePositionSide fromWireCode(int wireCode) {
        return Arrays.stream(values()).filter(value -> value.wireCode == wireCode).findFirst()
                .orElseThrow(() -> new ProtocolException("unsupported position side: " + wireCode));
    }
}
