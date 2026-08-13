package com.surprising.aeron.protocol;

public record AdjustInsuranceFundCommand(String asset, long deltaUnits) {
    public AdjustInsuranceFundCommand {
        if (asset == null || asset.isBlank() || deltaUnits == 0) {
            throw new IllegalArgumentException("invalid insurance fund adjustment");
        }
        asset = asset.trim().toUpperCase();
        if (!asset.matches("[A-Z0-9]{2,20}")) {
            throw new IllegalArgumentException("invalid insurance fund asset");
        }
    }
}
