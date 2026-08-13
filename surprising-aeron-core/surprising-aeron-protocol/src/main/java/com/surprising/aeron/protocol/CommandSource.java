package com.surprising.aeron.protocol;

import java.util.Arrays;

public enum CommandSource {
    GATEWAY(1),
    KAFKA_INPUT_BRIDGE(2),
    SCHEDULER(3),
    OPERATIONS(4),
    RECOVERY_TOOL(5);

    private final int wireCode;

    CommandSource(int wireCode) {
        this.wireCode = wireCode;
    }

    public int wireCode() {
        return wireCode;
    }

    public static CommandSource fromWireCode(int wireCode) {
        return Arrays.stream(values())
                .filter(value -> value.wireCode == wireCode)
                .findFirst()
                .orElseThrow(() -> new ProtocolException("unsupported command source: " + wireCode));
    }
}
