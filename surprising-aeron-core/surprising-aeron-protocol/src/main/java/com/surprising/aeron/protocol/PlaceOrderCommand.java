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
        CoreMarginMode marginMode,
        CorePositionSide positionSide,
        ReservationKind reservationKind,
        String reservationAsset,
        long reservedUnits) {

    public PlaceOrderCommand {
        if (orderId <= 0 || symbol == null || symbol.isBlank() || instrumentVersion <= 0
                || baseAsset == null || baseAsset.isBlank()
                || quoteAsset == null || quoteAsset.isBlank() || side == null || priceTicks < 0
                || settleAsset == null || settleAsset.isBlank()
                || quantitySteps <= 0 || marginMode == null || positionSide == null
                || reservationKind == null || reservationAsset == null
                || reservationAsset.isBlank() || reservedUnits <= 0) {
            throw new IllegalArgumentException("invalid place order command");
        }
    }

    public PlaceOrderCommand(long orderId, String symbol, long instrumentVersion, String baseAsset,
                             String quoteAsset, String settleAsset, CoreOrderSide side, long priceTicks,
                             long quantitySteps, boolean reduceOnly, ReservationKind reservationKind,
                             String reservationAsset, long reservedUnits) {
        this(orderId, symbol, instrumentVersion, baseAsset, quoteAsset, settleAsset, side, priceTicks,
                quantitySteps, reduceOnly, CoreMarginMode.CROSS, CorePositionSide.NET,
                reservationKind, reservationAsset, reservedUnits);
    }
}
