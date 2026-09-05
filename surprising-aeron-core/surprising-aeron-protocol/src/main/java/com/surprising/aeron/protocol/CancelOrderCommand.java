package com.surprising.aeron.protocol;

public record CancelOrderCommand(long orderId) {

    public CancelOrderCommand {
        if (orderId <= 0) {
            throw new IllegalArgumentException("orderId must be positive");
        }
    }
}
