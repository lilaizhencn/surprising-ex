package com.surprising.gateway.provider.service;

public enum ProductTransferStatus {
    PENDING,
    SOURCE_DEBIT_UNKNOWN,
    SOURCE_DEBITED,
    TARGET_CREDIT_UNKNOWN,
    COMPENSATION_REQUIRED,
    COMPLETED,
    FAILED;

    public boolean terminal() {
        return this == COMPLETED || this == FAILED;
    }
}
