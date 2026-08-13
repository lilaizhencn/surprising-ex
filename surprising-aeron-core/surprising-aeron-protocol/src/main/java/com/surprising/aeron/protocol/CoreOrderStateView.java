package com.surprising.aeron.protocol;

import com.surprising.product.api.ProductLine;
import java.util.UUID;

public record CoreOrderStateView(
        long orderId,
        ProductLine productLine,
        long userId,
        String symbol,
        long instrumentVersion,
        CoreOrderSide side,
        long priceTicks,
        long quantitySteps,
        long executedQuantitySteps,
        long remainingQuantitySteps,
        boolean reduceOnly,
        CoreMarginMode marginMode,
        CorePositionSide positionSide,
        CoreOrderType orderType,
        CoreTimeInForce timeInForce,
        boolean postOnly,
        String clientOrderId,
        UUID commandId,
        long makerFeeRatePpm,
        long takerFeeRatePpm,
        long createdAtEpochMillis,
        long updatedAtEpochMillis,
        long clusterPosition,
        String status,
        long revision) {

    public CoreOrderStateView(long orderId, ProductLine productLine, long userId, String symbol,
                              long instrumentVersion, CoreOrderSide side, long priceTicks, long quantitySteps,
                              long executedQuantitySteps, long remainingQuantitySteps, boolean reduceOnly,
                              String status, long revision) {
        this(orderId, productLine, userId, symbol, instrumentVersion, side, priceTicks, quantitySteps,
                executedQuantitySteps, remainingQuantitySteps, reduceOnly, CoreMarginMode.CROSS,
                CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "",
                new UUID(0, orderId), 0, 0, 0, 0, 0, status, revision);
    }
}
