package com.surprising.aeron.protocol;

public record PlaceOrderCommand(
        long orderId,
        String symbol,
        long instrumentVersion,
        String baseAsset,
        String quoteAsset,
        String settleAsset,
        CoreOrderSide side,
        long priceTicks,
        long quantitySteps,
        boolean reduceOnly,
        ReservationKind reservationKind,
        String reservationAsset,
        long reservedUnits) {

    public PlaceOrderCommand {
        if (orderId <= 0 || symbol == null || symbol.isBlank() || instrumentVersion <= 0
                || baseAsset == null || baseAsset.isBlank()
                || quoteAsset == null || quoteAsset.isBlank() || side == null || priceTicks < 0
                || settleAsset == null || settleAsset.isBlank()
                || quantitySteps <= 0 || reservationKind == null || reservationAsset == null
                || reservationAsset.isBlank() || reservedUnits <= 0) {
            throw new IllegalArgumentException("invalid place order command");
        }
    }
}
