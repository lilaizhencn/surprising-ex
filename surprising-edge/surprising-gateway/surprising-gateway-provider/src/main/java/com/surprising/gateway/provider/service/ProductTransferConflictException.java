package com.surprising.gateway.provider.service;

public class ProductTransferConflictException extends IllegalArgumentException {

    public ProductTransferConflictException(String message) {
        super(message);
    }
}
