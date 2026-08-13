package com.surprising.aeron.protocol;

import java.util.Arrays;

public enum CoreMessageType {
    PROBE_INCREMENT(1, WireMessageKind.COMMAND),
    VERIFY_STATE_HASH(2, WireMessageKind.COMMAND),
    STATE_HASH_QUERY(100, WireMessageKind.QUERY),
    COMMAND_RESULT(200, WireMessageKind.RESPONSE),
    STATE_HASH_RESULT(201, WireMessageKind.RESPONSE),
    CORE_EVENT(300, WireMessageKind.EXPORT_EVENT);

    private final int wireCode;
    private final WireMessageKind kind;

    CoreMessageType(int wireCode, WireMessageKind kind) {
        this.wireCode = wireCode;
        this.kind = kind;
    }

    public int wireCode() {
        return wireCode;
    }

    public WireMessageKind kind() {
        return kind;
    }

    public static CoreMessageType fromWireCode(int wireCode) {
        return Arrays.stream(values())
                .filter(value -> value.wireCode == wireCode)
                .findFirst()
                .orElseThrow(() -> new ProtocolException("unsupported message type: " + wireCode));
    }
}
