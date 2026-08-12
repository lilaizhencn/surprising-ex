package com.surprising.funding.provider.service;

public class StaleFundingRateException extends IllegalStateException {

    public StaleFundingRateException(String message) {
        super(message);
    }
}
