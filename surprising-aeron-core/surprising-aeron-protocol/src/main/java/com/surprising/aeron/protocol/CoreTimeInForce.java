package com.surprising.aeron.protocol;

public enum CoreTimeInForce {
    GTC(1),
    IOC(2),
    FOK(3),
    GTX(4);

    private final int wireCode;

    CoreTimeInForce(int wireCode) {
        this.wireCode = wireCode;
    }

    public int wireCode() {
        return wireCode;
    }

    public boolean immediate() {
        return this == IOC || this == FOK;
    }

    public static CoreTimeInForce fromWireCode(int wireCode) {
        return switch (wireCode) {
            case 1 -> GTC;
            case 2 -> IOC;
            case 3 -> FOK;
            case 4 -> GTX;
            default -> throw new ProtocolException("unsupported time in force: " + wireCode);
        };
    }
}
