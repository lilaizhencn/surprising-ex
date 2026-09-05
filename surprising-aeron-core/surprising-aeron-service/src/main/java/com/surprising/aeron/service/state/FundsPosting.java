package com.surprising.aeron.service.state;

public record FundsPosting(String asset, OwnerKind ownerKind, long ownerId, Subledger subledger, long units) {

    public FundsPosting {
        if (asset == null || ownerKind == null || subledger == null || units == 0) {
            throw new IllegalArgumentException("invalid funds posting");
        }
        asset = AssetBalance.normalizeAsset(asset);
        boolean externalOwner = ownerKind == OwnerKind.EXTERNAL;
        if ((ownerKind == OwnerKind.USER || ownerKind == OwnerKind.MAKER) && ownerId <= 0
                || (ownerKind == OwnerKind.TREASURY || externalOwner) && ownerId != 0
                || externalOwner != (subledger == Subledger.EXTERNAL_ADJUSTMENT)) {
            throw new IllegalArgumentException("invalid funds posting owner");
        }
    }

    public enum OwnerKind {
        USER(1),
        MAKER(2),
        TREASURY(3),
        EXTERNAL(4);

        private final int wireCode;

        OwnerKind(int wireCode) {
            this.wireCode = wireCode;
        }

        int wireCode() {
            return wireCode;
        }
    }

    public enum Subledger {
        AVAILABLE(1),
        LOCKED(2),
        RESERVATION(3),
        POSITION_MARGIN(4),
        FEE(5),
        INSURANCE(6),
        LIQUIDATION_FEE(7),
        FUNDING_RESIDUAL(8),
        ROUNDING_RESIDUAL(9),
        CLEARING_PNL(10),
        DEFICIT(11),
        EXTERNAL_ADJUSTMENT(12);

        private final int wireCode;

        Subledger(int wireCode) {
            this.wireCode = wireCode;
        }

        int wireCode() {
            return wireCode;
        }
    }
}
