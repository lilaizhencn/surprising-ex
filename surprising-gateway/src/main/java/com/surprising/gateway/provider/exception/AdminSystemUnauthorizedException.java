package com.surprising.gateway.provider.exception;

public class AdminSystemUnauthorizedException extends RuntimeException {
        public AdminSystemUnauthorizedException(String message, Throwable cause) {
            super(message, cause);
        }
}
