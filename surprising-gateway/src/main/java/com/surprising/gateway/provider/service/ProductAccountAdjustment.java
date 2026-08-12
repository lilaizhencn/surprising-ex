package com.surprising.gateway.provider.service;

public record ProductAccountAdjustment(Status status, String errorMessage) {

    enum Status {
        APPLIED,
        REJECTED,
        UNKNOWN
    }

    static ProductAccountAdjustment applied(String message) {
        return new ProductAccountAdjustment(Status.APPLIED, message);
    }

    static ProductAccountAdjustment rejected(String message) {
        return new ProductAccountAdjustment(Status.REJECTED, message);
    }

    static ProductAccountAdjustment unknown(String message) {
        return new ProductAccountAdjustment(Status.UNKNOWN, message);
    }
}
