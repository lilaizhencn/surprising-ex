package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreTimeInForce;

public record OrderRuntime(long orderId, long userId, int symbolId, long instrumentVersion,
                           CoreOrderSide side, long priceTicks, boolean reduceOnly,
                           CoreMarginMode marginMode, CorePositionSide positionSide,
                           CoreOrderType orderType, CoreTimeInForce timeInForce,
                           long makerFeeRatePpm, long takerFeeRatePpm, long quantitySteps,
                           long executedQuantitySteps, long remainingQuantitySteps, boolean canceled) {
    public OrderRuntime(long orderId, long userId, int symbolId, long quantitySteps) {
        this(orderId, userId, symbolId, 1, CoreOrderSide.BUY, 0, false, CoreMarginMode.CROSS,
                CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, 0, 0,
                quantitySteps, 0, quantitySteps, false);
    }

    public OrderRuntime(long orderId, long userId, int symbolId, long quantitySteps, boolean canceled) {
        this(orderId, userId, symbolId, 1, CoreOrderSide.BUY, 0, false, CoreMarginMode.CROSS,
                CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, 0, 0,
                quantitySteps, 0, quantitySteps, canceled);
    }

    public OrderRuntime {
        if (orderId <= 0 || userId <= 0 || symbolId < 0 || instrumentVersion <= 0 || side == null
                || priceTicks < 0 || marginMode == null || positionSide == null || orderType == null
                || timeInForce == null || quantitySteps <= 0
                || executedQuantitySteps < 0 || remainingQuantitySteps < 0
                || Math.addExact(executedQuantitySteps, remainingQuantitySteps) != quantitySteps) {
            throw new IllegalArgumentException("invalid runtime order");
        }
    }
}
