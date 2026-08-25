package com.surprising.aeron.protocol;

public record CoreTreasuryAssetView(
        String asset,
        long feeBalanceUnits,
        long insuranceBalanceUnits,
        long insuranceDeficitUnits,
        long liquidationFeeBalanceUnits,
        long fundingResidualBalanceUnits,
        long roundingResidualBalanceUnits,
        long clearingPnlBalanceUnits) {

    public CoreTreasuryAssetView(String asset, long feeBalanceUnits,
                                 long insuranceBalanceUnits, long insuranceDeficitUnits) {
        this(asset, feeBalanceUnits, insuranceBalanceUnits, insuranceDeficitUnits, 0, 0, 0, 0);
    }

    public CoreTreasuryAssetView {
        if (asset == null || asset.isBlank() || insuranceBalanceUnits < 0 || insuranceDeficitUnits < 0) {
            throw new IllegalArgumentException("invalid core treasury asset view");
        }
    }
}
