package com.surprising.gateway.provider.exception;

public class AdminApprovalRequiredException extends RuntimeException {

    public AdminApprovalRequiredException(String message) {
        super(message);
    }

    public AdminApprovalRequiredException(String message, Throwable cause) {
        super(message, cause);
    }
}
