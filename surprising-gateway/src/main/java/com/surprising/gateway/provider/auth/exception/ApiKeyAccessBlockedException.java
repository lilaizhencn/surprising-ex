package com.surprising.gateway.provider.auth.exception;

public class ApiKeyAccessBlockedException extends RuntimeException {
    public ApiKeyAccessBlockedException(String message, Throwable cause) { super(message, cause); }
}
