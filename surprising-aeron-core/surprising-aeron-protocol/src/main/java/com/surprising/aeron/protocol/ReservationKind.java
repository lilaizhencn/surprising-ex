package com.surprising.aeron.protocol;

import java.util.Arrays;

public enum ReservationKind {
    SPOT_ASSET(1),
    DERIVATIVE_MARGIN(2);

    private final int wireCode;

    ReservationKind(int wireCode) {
        this.wireCode = wireCode;
    }

    public int wireCode() {
        return wireCode;
    }

    public static ReservationKind fromWireCode(int wireCode) {
        return Arrays.stream(values())
                .filter(value -> value.wireCode == wireCode)
                .findFirst()
                .orElseThrow(() -> new ProtocolException("unsupported reservation kind: " + wireCode));
    }
}
