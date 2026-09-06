package com.surprising.aeron.protocol;

public enum WireMessageKind {
    COMMAND(1),
    RESPONSE(2),
    QUERY(3),
    EXPORT_EVENT(4);

    private static final WireMessageKind[] BY_WIRE_CODE;

    static {
        WireMessageKind[] constants = values();
        int max = 0;
        for (WireMessageKind value : constants) max = Math.max(max, value.wireCode);
        BY_WIRE_CODE = new WireMessageKind[max + 1];
        for (WireMessageKind value : constants) {
            if (value.wireCode < 0 || BY_WIRE_CODE[value.wireCode] != null) {
                throw new ExceptionInInitializerError("duplicate or negative wire code");
            }
            BY_WIRE_CODE[value.wireCode] = value;
        }
    }

    private final int wireCode;

    WireMessageKind(int wireCode) {
        this.wireCode = wireCode;
    }

    public int wireCode() {
        return wireCode;
    }

    public static WireMessageKind fromWireCode(int wireCode) {
        if (wireCode >= 0 && wireCode < BY_WIRE_CODE.length) {
            WireMessageKind value = BY_WIRE_CODE[wireCode];
            if (value != null) return value;
        }
        throw new ProtocolException("unsupported message kind: " + wireCode);
    }
}
