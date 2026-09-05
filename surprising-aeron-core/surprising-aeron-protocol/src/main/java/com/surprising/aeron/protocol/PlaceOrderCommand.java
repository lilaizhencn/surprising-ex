package com.surprising.aeron.protocol;

public record PlaceOrderCommand(
        long orderId,
        String symbol,
        long instrumentVersion,
        CoreOrderSide side,
        long limitPriceTicks,
        long quantitySteps,
        boolean reduceOnly,
        CoreMarginMode marginMode,
        CorePositionSide positionSide,
        CoreOrderType orderType,
        CoreTimeInForce timeInForce,
        boolean postOnly,
        String clientOrderId) {

    public PlaceOrderCommand {
        if (orderId <= 0 || symbol == null || symbol.isBlank() || instrumentVersion <= 0
                || side == null || limitPriceTicks < 0
                || quantitySteps <= 0 || marginMode == null || positionSide == null
                || orderType == null || timeInForce == null
                || clientOrderId == null || clientOrderId.length() > 64
                || orderType == CoreOrderType.LIMIT && limitPriceTicks <= 0
                || orderType == CoreOrderType.MARKET && limitPriceTicks != 0
                || postOnly && (orderType != CoreOrderType.LIMIT || timeInForce != CoreTimeInForce.GTX)) {
            throw new IllegalArgumentException("invalid place order command");
        }
    }
}
