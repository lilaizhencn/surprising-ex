package com.surprising.aeron.protocol;

public record PlaceOrderCommand(
        long orderId,
        String symbol,
        long instrumentVersion,
        String baseAsset,
        String quoteAsset,
        String settleAsset,
        CoreOrderSide side,
        long limitPriceTicks,
        long executionPriceTicks,
        long reservationPriceTicks,
        long markPriceTicks,
        long quantitySteps,
        boolean reduceOnly,
        CoreMarginMode marginMode,
        CorePositionSide positionSide,
        ReservationKind reservationKind,
        String reservationAsset,
        long reservedUnits,
        CoreOrderType orderType,
        CoreTimeInForce timeInForce,
        boolean postOnly,
        String clientOrderId,
        long makerFeeRatePpm,
        long takerFeeRatePpm) {

    public PlaceOrderCommand {
        if (orderId <= 0 || symbol == null || symbol.isBlank() || instrumentVersion <= 0
                || baseAsset == null || baseAsset.isBlank()
                || quoteAsset == null || quoteAsset.isBlank() || side == null
                || settleAsset == null || settleAsset.isBlank()
                || limitPriceTicks <= 0 || executionPriceTicks <= 0
                || reservationPriceTicks <= 0 || markPriceTicks <= 0
                || quantitySteps <= 0 || marginMode == null || positionSide == null
                || reservationKind == null || reservationAsset == null
                || reservationAsset.isBlank() || reservedUnits < 0 || orderType == null || timeInForce == null
                || clientOrderId == null || clientOrderId.length() > 64
                || makerFeeRatePpm < -1_000_000 || makerFeeRatePpm > 1_000_000
                || takerFeeRatePpm < -1_000_000 || takerFeeRatePpm > 1_000_000
                || makerFeeRatePpm > takerFeeRatePpm
                || postOnly && (orderType != CoreOrderType.LIMIT || timeInForce != CoreTimeInForce.GTX)) {
            throw new IllegalArgumentException("invalid place order command");
        }
    }
}
