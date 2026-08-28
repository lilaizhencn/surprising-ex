package com.surprising.aeron.protocol;

public enum CoreMarginMode {
    CROSS(0),
    ISOLATED(1);

    private final int wireCode;

    CoreMarginMode(int wireCode) {
        this.wireCode = wireCode;
    }

    public int wireCode() {
        return wireCode;
    }

    public static CoreMarginMode fromWireCode(int wireCode) {
        return switch (wireCode) {
            case 0 -> CROSS;
            case 1 -> ISOLATED;
            default -> throw new ProtocolException("unsupported margin mode: " + wireCode);
        };
    }
}
