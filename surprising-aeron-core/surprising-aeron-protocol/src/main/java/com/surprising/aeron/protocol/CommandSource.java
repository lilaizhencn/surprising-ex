package com.surprising.aeron.protocol;

public enum CommandSource {
    GATEWAY(1),
    KAFKA_INPUT_BRIDGE(2),
    SCHEDULER(3),
    OPERATIONS(4),
    RECOVERY_TOOL(5);

    private static final CommandSource[] BY_WIRE_CODE;

    static {
        CommandSource[] constants = values();
        int max = 0;
        for (CommandSource value : constants) max = Math.max(max, value.wireCode);
        BY_WIRE_CODE = new CommandSource[max + 1];
        for (CommandSource value : constants) {
            if (value.wireCode < 0 || BY_WIRE_CODE[value.wireCode] != null) {
                throw new ExceptionInInitializerError("duplicate or negative wire code");
            }
            BY_WIRE_CODE[value.wireCode] = value;
        }
    }

    private final int wireCode;

    CommandSource(int wireCode) {
        this.wireCode = wireCode;
    }

    public int wireCode() {
        return wireCode;
    }

    public static CommandSource fromWireCode(int wireCode) {
        if (wireCode >= 0 && wireCode < BY_WIRE_CODE.length) {
            CommandSource value = BY_WIRE_CODE[wireCode];
            if (value != null) return value;
        }
        throw new ProtocolException("unsupported command source: " + wireCode);
    }
}
