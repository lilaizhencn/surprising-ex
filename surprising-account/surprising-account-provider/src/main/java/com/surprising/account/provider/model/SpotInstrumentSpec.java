package com.surprising.account.provider.model;

public record SpotInstrumentSpec(
        long version,
        String baseAsset,
        String quoteAsset,
        long quantityStepUnits,
        long notionalMultiplierUnits) {

    public SpotInstrumentSpec {
        if (version <= 0) {
            throw new IllegalArgumentException("version must be positive");
        }
        if (baseAsset == null || baseAsset.isBlank()) {
            throw new IllegalArgumentException("baseAsset is required");
        }
        if (quoteAsset == null || quoteAsset.isBlank()) {
            throw new IllegalArgumentException("quoteAsset is required");
        }
        if (quantityStepUnits <= 0 || notionalMultiplierUnits <= 0) {
            throw new IllegalArgumentException("spot unit fields must be positive");
        }
    }
}
