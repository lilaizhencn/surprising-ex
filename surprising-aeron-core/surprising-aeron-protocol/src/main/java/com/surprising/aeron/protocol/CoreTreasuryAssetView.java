package com.surprising.aeron.protocol;

public record CoreTreasuryAssetView(
        String asset,
        long feeBalanceUnits,
        long insuranceBalanceUnits,
        long insuranceDeficitUnits) {

    public CoreTreasuryAssetView {
        if (asset == null || asset.isBlank() || insuranceBalanceUnits < 0 || insuranceDeficitUnits < 0) {
            throw new IllegalArgumentException("invalid core treasury asset view");
        }
    }
}
