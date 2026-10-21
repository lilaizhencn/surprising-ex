package com.surprising.gateway.provider.exception;

public class SupportTicketNotFoundException extends RuntimeException {
    public SupportTicketNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
