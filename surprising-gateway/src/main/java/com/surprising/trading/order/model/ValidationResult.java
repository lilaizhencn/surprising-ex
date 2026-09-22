package com.surprising.trading.order.model;

import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.InstrumentType;

public record ValidationResult(
        boolean accepted,
        String rejectReason,
        long instrumentChangeId,
        InstrumentType instrumentType,
        ContractType contractType) {

    public ValidationResult {
        instrumentType = instrumentType == null ? InstrumentType.PERPETUAL : instrumentType;
        contractType = contractType == null ? defaultContractType(instrumentType) : contractType;
    }

    public static ValidationResult ok() {
        return ok(0L);
    }

    public static ValidationResult ok(long instrumentChangeId) {
        return ok(instrumentChangeId, InstrumentType.PERPETUAL);
    }

    public static ValidationResult ok(long instrumentChangeId, InstrumentType instrumentType) {
        return ok(instrumentChangeId, instrumentType, defaultContractType(instrumentType));
    }

    public static ValidationResult ok(long instrumentChangeId,
                                      InstrumentType instrumentType,
                                      ContractType contractType) {
        return new ValidationResult(true, null, instrumentChangeId, instrumentType, contractType);
    }

    public static ValidationResult reject(String rejectReason) {
        return reject(rejectReason, 0L);
    }

    public static ValidationResult reject(String rejectReason, long instrumentChangeId) {
        return reject(rejectReason, instrumentChangeId, InstrumentType.PERPETUAL);
    }

    public static ValidationResult reject(String rejectReason, long instrumentChangeId, InstrumentType instrumentType) {
        return reject(rejectReason, instrumentChangeId, instrumentType, defaultContractType(instrumentType));
    }

    public static ValidationResult reject(String rejectReason,
                                          long instrumentChangeId,
                                          InstrumentType instrumentType,
                                          ContractType contractType) {
        return new ValidationResult(false, rejectReason, instrumentChangeId, instrumentType, contractType);
    }

    private static ContractType defaultContractType(InstrumentType instrumentType) {
        return instrumentType == InstrumentType.SPOT ? ContractType.SPOT : ContractType.LINEAR_PERPETUAL;
    }
}
