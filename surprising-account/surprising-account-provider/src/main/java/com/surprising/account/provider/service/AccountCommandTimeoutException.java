package com.surprising.account.provider.service;

public class AccountCommandTimeoutException extends RuntimeException {

    public AccountCommandTimeoutException(String message) {
        super(message);
    }
}
