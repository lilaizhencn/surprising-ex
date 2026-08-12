package com.surprising.gateway.provider.service;

public class KycDocumentNotFoundException extends RuntimeException {
    public KycDocumentNotFoundException(String message) {
        super(message);
    }
}
