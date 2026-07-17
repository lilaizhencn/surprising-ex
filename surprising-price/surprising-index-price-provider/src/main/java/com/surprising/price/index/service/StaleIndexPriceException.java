package com.surprising.price.index.service;

public class StaleIndexPriceException extends IllegalStateException {

    public StaleIndexPriceException(String message) {
        super(message);
    }
}
