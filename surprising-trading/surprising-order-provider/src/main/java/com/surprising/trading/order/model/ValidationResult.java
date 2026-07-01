package com.surprising.trading.order.model;

public record ValidationResult(
        boolean accepted,
        String rejectReason,
        long instrumentVersion) {

    public static ValidationResult ok() {
        return ok(0L);
    }

    public static ValidationResult ok(long instrumentVersion) {
        return new ValidationResult(true, null, instrumentVersion);
    }

    public static ValidationResult reject(String rejectReason) {
        return new ValidationResult(false, rejectReason, 0L);
    }

    public static ValidationResult reject(String rejectReason, long instrumentVersion) {
        return new ValidationResult(false, rejectReason, instrumentVersion);
    }
}
