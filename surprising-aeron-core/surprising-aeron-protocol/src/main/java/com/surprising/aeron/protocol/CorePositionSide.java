package com.surprising.aeron.protocol;

public enum CorePositionSide {
    NET(0),
    LONG(1),
    SHORT(2);

    private final int wireCode;

    CorePositionSide(int wireCode) {
        this.wireCode = wireCode;
    }

    public int wireCode() {
        return wireCode;
    }

    public boolean hedgeSide() {
        return this != NET;
    }

    public static CorePositionSide fromWireCode(int wireCode) {
        return switch (wireCode) {
            case 0 -> NET;
            case 1 -> LONG;
            case 2 -> SHORT;
            default -> throw new ProtocolException("unsupported position side: " + wireCode);
        };
    }
}
