package com.surprising.trading.order.model;

import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.InstrumentType;

public record ValidationResult(
        boolean accepted,
        String rejectReason,
        long instrumentVersion,
        InstrumentType instrumentType,
        ContractType contractType) {

    public ValidationResult {
        instrumentType = instrumentType == null ? InstrumentType.PERPETUAL : instrumentType;
        contractType = contractType == null ? defaultContractType(instrumentType) : contractType;
    }

    public static ValidationResult ok() {
        return ok(0L);
    }

    public static ValidationResult ok(long instrumentVersion) {
        return ok(instrumentVersion, InstrumentType.PERPETUAL);
    }

    public static ValidationResult ok(long instrumentVersion, InstrumentType instrumentType) {
        return ok(instrumentVersion, instrumentType, defaultContractType(instrumentType));
    }

    public static ValidationResult ok(long instrumentVersion,
                                      InstrumentType instrumentType,
                                      ContractType contractType) {
        return new ValidationResult(true, null, instrumentVersion, instrumentType, contractType);
    }

    public static ValidationResult reject(String rejectReason) {
        return reject(rejectReason, 0L);
    }

    public static ValidationResult reject(String rejectReason, long instrumentVersion) {
        return reject(rejectReason, instrumentVersion, InstrumentType.PERPETUAL);
    }

    public static ValidationResult reject(String rejectReason, long instrumentVersion, InstrumentType instrumentType) {
        return reject(rejectReason, instrumentVersion, instrumentType, defaultContractType(instrumentType));
    }

    public static ValidationResult reject(String rejectReason,
                                          long instrumentVersion,
                                          InstrumentType instrumentType,
                                          ContractType contractType) {
        return new ValidationResult(false, rejectReason, instrumentVersion, instrumentType, contractType);
    }

    private static ContractType defaultContractType(InstrumentType instrumentType) {
        return instrumentType == InstrumentType.SPOT ? ContractType.SPOT : ContractType.LINEAR_PERPETUAL;
    }
}
