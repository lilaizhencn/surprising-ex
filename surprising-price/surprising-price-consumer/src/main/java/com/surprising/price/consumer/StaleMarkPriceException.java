package com.surprising.price.consumer;

public class StaleMarkPriceException extends IllegalStateException {

    public StaleMarkPriceException(String message) {
        super(message);
    }
}
