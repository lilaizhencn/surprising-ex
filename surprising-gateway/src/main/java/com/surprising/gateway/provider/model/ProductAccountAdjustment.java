package com.surprising.gateway.provider.model;

public record ProductAccountAdjustment(Status status, String errorMessage) {

    public enum Status {
        APPLIED,
        REJECTED,
        UNKNOWN
    }

    public static ProductAccountAdjustment applied(String message) {
        return new ProductAccountAdjustment(Status.APPLIED, message);
    }

    public static ProductAccountAdjustment rejected(String message) {
        return new ProductAccountAdjustment(Status.REJECTED, message);
    }

    public static ProductAccountAdjustment unknown(String message) {
        return new ProductAccountAdjustment(Status.UNKNOWN, message);
    }
}
