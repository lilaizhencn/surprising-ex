package com.surprising.aeron.protocol;

import java.util.Arrays;

public enum CoreCancelAllAfterStatus {
    DISABLED(0),
    ACTIVE(1),
    TRIGGERING(2),
    TRIGGERED(3);

    private final int wireCode;

    CoreCancelAllAfterStatus(int wireCode) {
        this.wireCode = wireCode;
    }

    public int wireCode() {
        return wireCode;
    }

    public static CoreCancelAllAfterStatus fromWireCode(int wireCode) {
        return Arrays.stream(values())
                .filter(value -> value.wireCode == wireCode)
                .findFirst()
                .orElseThrow(() -> new ProtocolException("unsupported cancel-all-after status: " + wireCode));
    }
}
