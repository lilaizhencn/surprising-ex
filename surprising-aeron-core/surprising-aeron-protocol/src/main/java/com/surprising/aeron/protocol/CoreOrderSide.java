package com.surprising.aeron.protocol;

public enum CoreOrderSide {
    BUY(1),
    SELL(2);

    private final int wireCode;

    CoreOrderSide(int wireCode) {
        this.wireCode = wireCode;
    }

    public int wireCode() {
        return wireCode;
    }

    public static CoreOrderSide fromWireCode(int wireCode) {
        return switch (wireCode) {
            case 1 -> BUY;
            case 2 -> SELL;
            default -> throw new ProtocolException("unsupported order side: " + wireCode);
        };
    }
}
