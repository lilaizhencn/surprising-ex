package com.surprising.aeron.protocol;

public record BalanceAdjustmentCommand(String asset, long deltaUnits) {

    public BalanceAdjustmentCommand {
        if (asset == null || asset.isBlank() || deltaUnits == 0) {
            throw new IllegalArgumentException("balance adjustment requires asset and non-zero delta");
        }
    }
}
