package com.surprising.gateway.provider.exception;

import com.surprising.gateway.provider.exception.KycDocumentStorageException;
public class KycDocumentStorageUnavailableException extends KycDocumentStorageException {
    public KycDocumentStorageUnavailableException(String message) {
        super(message);
    }
}
