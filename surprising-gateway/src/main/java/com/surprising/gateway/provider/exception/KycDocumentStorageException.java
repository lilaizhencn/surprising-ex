package com.surprising.gateway.provider.exception;

public class KycDocumentStorageException extends RuntimeException {
    public KycDocumentStorageException(String message) {
        super(message);
    }

    public KycDocumentStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
