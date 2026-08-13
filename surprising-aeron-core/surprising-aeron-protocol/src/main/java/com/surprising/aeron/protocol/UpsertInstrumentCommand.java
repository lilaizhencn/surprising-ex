package com.surprising.aeron.protocol;

public record UpsertInstrumentCommand(
        String symbol,
        long instrumentVersion,
        int contractTypeCode,
        String baseAsset,
        String quoteAsset,
        String settleAsset,
        long notionalMultiplierUnits,
        long priceTickUnits,
        long settleScaleUnits,
        long initialMarginRatePpm,
        long maintenanceMarginRatePpm,
        long makerFeeRatePpm,
        long takerFeeRatePpm,
        long expiryEpochMillis,
        int optionTypeCode,
        long strikePriceTicks) {

    public UpsertInstrumentCommand {
        if (symbol == null || symbol.isBlank() || instrumentVersion <= 0 || contractTypeCode < 0
                || baseAsset == null || baseAsset.isBlank() || quoteAsset == null || quoteAsset.isBlank()
                || settleAsset == null || settleAsset.isBlank() || notionalMultiplierUnits <= 0
                || priceTickUnits <= 0 || settleScaleUnits <= 0 || initialMarginRatePpm <= 0
                || initialMarginRatePpm > 1_000_000 || maintenanceMarginRatePpm <= 0
                || maintenanceMarginRatePpm > 1_000_000
                || Math.absExact(makerFeeRatePpm) > 1_000_000 || Math.absExact(takerFeeRatePpm) > 1_000_000
                || expiryEpochMillis < 0 || optionTypeCode < -1 || strikePriceTicks < 0) {
            throw new IllegalArgumentException("invalid instrument command");
        }
    }
}
