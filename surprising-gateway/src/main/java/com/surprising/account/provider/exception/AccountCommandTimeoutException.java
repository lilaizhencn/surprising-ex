package com.surprising.account.provider.exception;

public class AccountCommandTimeoutException extends RuntimeException {

    public AccountCommandTimeoutException(String message) {
        super(message);
    }
}
