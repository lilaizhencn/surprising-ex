package com.surprising.aeron.service.state;

public final class CoreStateRejectedException extends RuntimeException {

    private final String code;

    public CoreStateRejectedException(String code, String message) {
        super(message);
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("rejection code is required");
        }
        this.code = code;
    }

    public String code() {
        return code;
    }
}
