package com.surprising.funding.provider.exception;

public class StaleFundingRateException extends IllegalStateException {

    public StaleFundingRateException(String message) {
        super(message);
    }
}
