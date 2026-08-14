package com.surprising.aeron.protocol;

import java.util.Arrays;

public enum CoreCancelAllAfterAction {
    SET(1),
    CLAIM(2),
    COMPLETE(3),
    RETRY(4);

    private final int wireCode;

    CoreCancelAllAfterAction(int wireCode) {
        this.wireCode = wireCode;
    }

    public int wireCode() {
        return wireCode;
    }

    public static CoreCancelAllAfterAction fromWireCode(int wireCode) {
        return Arrays.stream(values())
                .filter(value -> value.wireCode == wireCode)
                .findFirst()
                .orElseThrow(() -> new ProtocolException("unsupported cancel-all-after action: " + wireCode));
    }
}
