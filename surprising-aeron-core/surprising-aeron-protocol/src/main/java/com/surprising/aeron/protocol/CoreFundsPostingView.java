package com.surprising.aeron.protocol;

public record CoreFundsPostingView(String asset, OwnerKind ownerKind, long ownerId,
                                   Subledger subledger, long units) {

    public CoreFundsPostingView {
        if (asset == null || asset.isBlank() || ownerKind == null || subledger == null || units == 0) {
            throw new IllegalArgumentException("invalid funds posting view");
        }
    }

    public enum OwnerKind {
        USER(1), MAKER(2), TREASURY(3), EXTERNAL(4);

        private final int wireCode;

        OwnerKind(int wireCode) { this.wireCode = wireCode; }
        public int wireCode() { return wireCode; }

        public static OwnerKind fromWireCode(int code) {
            for (OwnerKind value : values()) if (value.wireCode == code) return value;
            throw new ProtocolException("unsupported funds owner kind: " + code);
        }
    }

    public enum Subledger {
        AVAILABLE(1), LOCKED(2), RESERVATION(3), POSITION_MARGIN(4), FEE(5), INSURANCE(6),
        LIQUIDATION_FEE(7), FUNDING_RESIDUAL(8), ROUNDING_RESIDUAL(9), CLEARING_PNL(10),
        DEFICIT(11), EXTERNAL_ADJUSTMENT(12);

        private final int wireCode;

        Subledger(int wireCode) { this.wireCode = wireCode; }
        public int wireCode() { return wireCode; }

        public static Subledger fromWireCode(int code) {
            for (Subledger value : values()) if (value.wireCode == code) return value;
            throw new ProtocolException("unsupported funds subledger: " + code);
        }
    }
}
