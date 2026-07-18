package com.surprising.account.provider.service;

public final class AccountCommandRejectedException extends RuntimeException {

    private final String errorCode;
    private final String resultPayload;

    public AccountCommandRejectedException(String errorCode, String message) {
        this(errorCode, message, null);
    }

    public AccountCommandRejectedException(String errorCode, String message, String resultPayload) {
        super(message);
        if (errorCode == null || errorCode.isBlank()) {
            throw new IllegalArgumentException("errorCode is required");
        }
        this.errorCode = errorCode;
        this.resultPayload = resultPayload;
    }

    public String errorCode() {
        return errorCode;
    }

    public String resultPayload() {
        return resultPayload;
    }
}
