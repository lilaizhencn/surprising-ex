package com.surprising.gateway.provider.exception;

public class SupportUnauthorizedException extends RuntimeException {
    public SupportUnauthorizedException(String message, Throwable cause) {
        super(message, cause);
    }
}
