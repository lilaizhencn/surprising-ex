package com.surprising.price.index.exception;

public class StaleIndexPriceException extends IllegalStateException {

    public StaleIndexPriceException(String message) {
        super(message);
    }
}
