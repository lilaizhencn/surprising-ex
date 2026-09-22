package com.surprising.account.provider.model;

import com.surprising.instrument.api.model.ContractType;

public record ContractSpec(
        long version,
        ContractType contractType,
        String settleAsset,
        long notionalMultiplierUnits,
        long priceTickUnits,
        long settleScaleUnits,
        long initialMarginRatePpm,
        long makerFeeRatePpm,
        long takerFeeRatePpm) {

    private static final long MAX_ABS_FEE_RATE_PPM = 1_000_000L;
    private static final long DEFAULT_INITIAL_MARGIN_RATE_PPM = 1_000_000L;

    public ContractSpec(long version,
                        ContractType contractType,
                        String settleAsset,
                        long notionalMultiplierUnits,
                        long priceTickUnits,
                        long settleScaleUnits,
                        long makerFeeRatePpm,
                        long takerFeeRatePpm) {
        this(version, contractType, settleAsset, notionalMultiplierUnits, priceTickUnits, settleScaleUnits,
                DEFAULT_INITIAL_MARGIN_RATE_PPM, makerFeeRatePpm, takerFeeRatePpm);
    }

    public ContractSpec {
        if (contractType == null) {
            throw new IllegalArgumentException("contractType is required");
        }
        if (version <= 0) {
            throw new IllegalArgumentException("version must be positive");
        }
        if (settleAsset == null || settleAsset.isBlank()) {
            throw new IllegalArgumentException("settleAsset is required");
        }
        if (notionalMultiplierUnits <= 0 || priceTickUnits <= 0 || settleScaleUnits <= 0) {
            throw new IllegalArgumentException("contract unit fields must be positive");
        }
        if (initialMarginRatePpm <= 0 || initialMarginRatePpm > 1_000_000L) {
            throw new IllegalArgumentException("initialMarginRatePpm must be in (0, 100%]");
        }
        if (makerFeeRatePpm < -MAX_ABS_FEE_RATE_PPM || makerFeeRatePpm > MAX_ABS_FEE_RATE_PPM
                || takerFeeRatePpm < -MAX_ABS_FEE_RATE_PPM || takerFeeRatePpm > MAX_ABS_FEE_RATE_PPM) {
            throw new IllegalArgumentException("fee rates must be within +/- 100%");
        }
    }
}
