package com.surprising.account.provider.service;

public class PositionCacheUnavailableException extends RuntimeException {

    public PositionCacheUnavailableException(String message) {
        super(message);
    }

    public PositionCacheUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
