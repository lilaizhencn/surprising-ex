package com.surprising.aeron.protocol;

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
        return switch (wireCode) {
            case 1 -> LIMIT;
            case 2 -> MARKET;
            default -> throw new ProtocolException("unsupported order type: " + wireCode);
        };
    }
}
