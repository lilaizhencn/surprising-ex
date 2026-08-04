package com.surprising.gateway.provider.service;

public class KycDocumentStorageException extends RuntimeException {
    public KycDocumentStorageException(String message) {
        super(message);
    }

    public KycDocumentStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
