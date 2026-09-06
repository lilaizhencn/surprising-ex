package com.surprising.aeron.protocol;

public enum ResponseStatus {
    APPLIED(1),
    DUPLICATE(2),
    OK(3),
    REJECTED(4);

    private static final ResponseStatus[] BY_WIRE_CODE;

    static {
        ResponseStatus[] constants = values();
        int max = 0;
        for (ResponseStatus value : constants) max = Math.max(max, value.wireCode);
        BY_WIRE_CODE = new ResponseStatus[max + 1];
        for (ResponseStatus value : constants) {
            if (value.wireCode < 0 || BY_WIRE_CODE[value.wireCode] != null) {
                throw new ExceptionInInitializerError("duplicate or negative wire code");
            }
            BY_WIRE_CODE[value.wireCode] = value;
        }
    }

    private final int wireCode;

    ResponseStatus(int wireCode) {
        this.wireCode = wireCode;
    }

    public int wireCode() {
        return wireCode;
    }

    public static ResponseStatus fromWireCode(int wireCode) {
        if (wireCode >= 0 && wireCode < BY_WIRE_CODE.length) {
            ResponseStatus value = BY_WIRE_CODE[wireCode];
            if (value != null) return value;
        }
        throw new ProtocolException("unsupported response status: " + wireCode);
    }
}
