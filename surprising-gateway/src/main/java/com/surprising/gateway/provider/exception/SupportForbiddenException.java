package com.surprising.gateway.provider.exception;

public class SupportForbiddenException extends RuntimeException {
    public SupportForbiddenException(String message, Throwable cause) {
        super(message, cause);
    }
}
