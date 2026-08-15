package com.surprising.aeron.protocol;

public record ReplaceOrderCommand(
        long originalOrderId,
        PlaceOrderCommand replacement) {

    public ReplaceOrderCommand {
        if (originalOrderId <= 0 || replacement == null || replacement.orderId() == originalOrderId) {
            throw new IllegalArgumentException("invalid replace order command");
        }
    }

}
