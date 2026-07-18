package com.surprising.account.provider.service;

public final class AccountCommandPoisonPillException extends RuntimeException {

    public AccountCommandPoisonPillException(String message, Throwable cause) {
        super(message, cause);
    }

    public AccountCommandPoisonPillException(String message) {
        super(message);
    }
}
