package com.surprising.aeron.protocol;

import java.util.Arrays;

public enum ResponseStatus {
    APPLIED(1),
    DUPLICATE(2),
    OK(3),
    REJECTED(4);

    private final int wireCode;

    ResponseStatus(int wireCode) {
        this.wireCode = wireCode;
    }

    public int wireCode() {
        return wireCode;
    }

    public static ResponseStatus fromWireCode(int wireCode) {
        return Arrays.stream(values())
                .filter(value -> value.wireCode == wireCode)
                .findFirst()
                .orElseThrow(() -> new ProtocolException("unsupported response status: " + wireCode));
    }
}
