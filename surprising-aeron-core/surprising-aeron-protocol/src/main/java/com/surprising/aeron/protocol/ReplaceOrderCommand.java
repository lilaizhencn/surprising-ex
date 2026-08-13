package com.surprising.aeron.protocol;

public record ReplaceOrderCommand(
        long originalOrderId,
        PlaceOrderCommand replacement) {

    public ReplaceOrderCommand {
        if (originalOrderId <= 0 || replacement == null || replacement.orderId() == originalOrderId) {
            throw new IllegalArgumentException("invalid replace order command");
        }
    }

    public ReplaceOrderCommand(long orderId, String baseAsset, String quoteAsset,
                               long newPriceTicks, long newReservedUnits) {
        this(orderId, new PlaceOrderCommand(Math.addExact(orderId, 1), "LEGACY", 1,
                baseAsset, quoteAsset, quoteAsset, CoreOrderSide.BUY, newPriceTicks, 1,
                false, ReservationKind.SPOT_ASSET, quoteAsset, newReservedUnits));
    }
}
