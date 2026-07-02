package com.surprising.trading.order.model;

import com.surprising.instrument.api.model.InstrumentType;

public record ValidationResult(
        boolean accepted,
        String rejectReason,
        long instrumentVersion,
        InstrumentType instrumentType) {

    public ValidationResult {
        instrumentType = instrumentType == null ? InstrumentType.PERPETUAL : instrumentType;
    }

    public static ValidationResult ok() {
        return ok(0L);
    }

    public static ValidationResult ok(long instrumentVersion) {
        return ok(instrumentVersion, InstrumentType.PERPETUAL);
    }

    public static ValidationResult ok(long instrumentVersion, InstrumentType instrumentType) {
        return new ValidationResult(true, null, instrumentVersion, instrumentType);
    }

    public static ValidationResult reject(String rejectReason) {
        return reject(rejectReason, 0L);
    }

    public static ValidationResult reject(String rejectReason, long instrumentVersion) {
        return reject(rejectReason, instrumentVersion, InstrumentType.PERPETUAL);
    }

    public static ValidationResult reject(String rejectReason, long instrumentVersion, InstrumentType instrumentType) {
        return new ValidationResult(false, rejectReason, instrumentVersion, instrumentType);
    }
}
