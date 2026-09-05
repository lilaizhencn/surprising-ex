package com.surprising.aeron.protocol;

import java.util.Arrays;

public enum WireMessageKind {
    COMMAND(1),
    RESPONSE(2),
    QUERY(3),
    EXPORT_EVENT(4);

    private final int wireCode;

    WireMessageKind(int wireCode) {
        this.wireCode = wireCode;
    }

    public int wireCode() {
        return wireCode;
    }

    public static WireMessageKind fromWireCode(int wireCode) {
        return Arrays.stream(values())
                .filter(value -> value.wireCode == wireCode)
                .findFirst()
                .orElseThrow(() -> new ProtocolException("unsupported message kind: " + wireCode));
    }
}
