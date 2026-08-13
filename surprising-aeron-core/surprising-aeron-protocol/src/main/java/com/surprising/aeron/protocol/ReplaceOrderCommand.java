package com.surprising.aeron.protocol;

public record ReplaceOrderCommand(
        long orderId,
        String baseAsset,
        String quoteAsset,
        long newPriceTicks,
        long newReservedUnits) {

    public ReplaceOrderCommand {
        if (orderId <= 0 || baseAsset == null || baseAsset.isBlank()
                || quoteAsset == null || quoteAsset.isBlank()
                || newPriceTicks <= 0 || newReservedUnits <= 0) {
            throw new IllegalArgumentException("invalid replace order command");
        }
    }
}
