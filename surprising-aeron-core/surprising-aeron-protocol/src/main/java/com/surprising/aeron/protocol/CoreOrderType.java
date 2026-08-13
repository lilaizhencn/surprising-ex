package com.surprising.aeron.protocol;

import java.util.Arrays;

public enum CoreOrderType {
    LIMIT(1),
    MARKET(2);

    private final int wireCode;

    CoreOrderType(int wireCode) {
        this.wireCode = wireCode;
    }

    public int wireCode() {
        return wireCode;
    }

    public static CoreOrderType fromWireCode(int wireCode) {
        return Arrays.stream(values()).filter(value -> value.wireCode == wireCode).findFirst()
                .orElseThrow(() -> new ProtocolException("unsupported order type: " + wireCode));
    }
}
