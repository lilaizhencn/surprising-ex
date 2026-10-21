package com.surprising.gateway.provider.model;

public enum ProductTransferStatus {
    PENDING,
    SOURCE_DEBITED,
    COMPLETED,
    FAILED;

    public boolean terminal() {
        return this == COMPLETED || this == FAILED;
    }
}
