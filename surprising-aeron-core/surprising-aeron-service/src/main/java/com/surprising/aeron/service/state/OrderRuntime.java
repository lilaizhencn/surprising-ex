package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.product.api.ProductLine;
import java.util.UUID;

public record OrderRuntime(long orderId, ProductLine productLine, long userId, int symbolId,
                           long instrumentVersion, CoreOrderSide side, long priceTicks,
                           long matchingPriceTicks,
                           long quantitySteps, long executedQuantitySteps, long remainingQuantitySteps,
                           boolean reduceOnly, CoreMarginMode marginMode, CorePositionSide positionSide,
                           CoreOrderType orderType, CoreTimeInForce timeInForce, boolean postOnly,
                           String clientOrderId, UUID commandId, long makerFeeRatePpm, long takerFeeRatePpm,
                           long createdAtEpochMillis, long updatedAtEpochMillis, long clusterPosition,
                           CoreOrderStatus status, long revision) {

    public OrderRuntime(long orderId, long userId, int symbolId, long quantitySteps) {
        this(orderId, userId, symbolId, quantitySteps, false);
    }

    public OrderRuntime(long orderId, long userId, int symbolId, long quantitySteps, boolean canceled) {
        this(orderId, userId, symbolId, 1, CoreOrderSide.BUY, 0, false, CoreMarginMode.CROSS,
                CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, 0, 0,
                quantitySteps, 0, quantitySteps, canceled);
    }

    public OrderRuntime(long orderId, long userId, int symbolId, long instrumentVersion,
                        CoreOrderSide side, long priceTicks, boolean reduceOnly,
                        CoreMarginMode marginMode, CorePositionSide positionSide,
                        CoreOrderType orderType, CoreTimeInForce timeInForce,
                        long makerFeeRatePpm, long takerFeeRatePpm, long quantitySteps,
                        long executedQuantitySteps, long remainingQuantitySteps, boolean canceled) {
        this(orderId, ProductLine.LINEAR_PERPETUAL, userId, symbolId, instrumentVersion, side, priceTicks,
                priceTicks,
                quantitySteps, executedQuantitySteps, remainingQuantitySteps, reduceOnly, marginMode,
                positionSide, orderType, timeInForce, false, "", new UUID(0, orderId), makerFeeRatePpm,
                takerFeeRatePpm, 0, 0, 0, canceled ? CoreOrderStatus.CANCELED : CoreOrderStatus.OPEN, 1);
    }

    public OrderRuntime {
        if (orderId <= 0 || productLine == null || userId <= 0 || symbolId < 0 || instrumentVersion <= 0
                || side == null || priceTicks < 0 || matchingPriceTicks < 0
                || quantitySteps <= 0 || executedQuantitySteps < 0
                || remainingQuantitySteps < 0
                || Math.addExact(executedQuantitySteps, remainingQuantitySteps) != quantitySteps
                || marginMode == null || positionSide == null || orderType == null || timeInForce == null
                || clientOrderId == null || clientOrderId.length() > 64 || commandId == null
                || createdAtEpochMillis < 0 || updatedAtEpochMillis < createdAtEpochMillis || clusterPosition < 0
                || postOnly && (orderType != CoreOrderType.LIMIT || timeInForce != CoreTimeInForce.GTX)
                || status == null || revision <= 0
                || status == CoreOrderStatus.OPEN && remainingQuantitySteps == 0) {
            throw new IllegalArgumentException("invalid runtime order");
        }
    }

    public OrderRuntime(long orderId, ProductLine productLine, long userId, int symbolId,
                        long instrumentVersion, CoreOrderSide side, long priceTicks,
                        long quantitySteps, long executedQuantitySteps, long remainingQuantitySteps,
                        boolean reduceOnly, CoreMarginMode marginMode, CorePositionSide positionSide,
                        CoreOrderType orderType, CoreTimeInForce timeInForce, boolean postOnly,
                        String clientOrderId, UUID commandId, long makerFeeRatePpm, long takerFeeRatePpm,
                        long createdAtEpochMillis, long updatedAtEpochMillis, long clusterPosition,
                        CoreOrderStatus status, long revision) {
        this(orderId, productLine, userId, symbolId, instrumentVersion, side, priceTicks, priceTicks,
                quantitySteps, executedQuantitySteps, remainingQuantitySteps, reduceOnly, marginMode,
                positionSide, orderType, timeInForce, postOnly, clientOrderId, commandId, makerFeeRatePpm,
                takerFeeRatePpm, createdAtEpochMillis, updatedAtEpochMillis, clusterPosition, status, revision);
    }

    public boolean canceled() {
        return status.terminal();
    }

    public OrderRuntime withExecution(long executed, long remaining, CoreOrderStatus nextStatus, long nextRevision) {
        return new OrderRuntime(orderId, productLine, userId, symbolId, instrumentVersion, side, priceTicks,
                matchingPriceTicks,
                quantitySteps, executed, remaining, reduceOnly, marginMode, positionSide, orderType, timeInForce,
                postOnly, clientOrderId, commandId, makerFeeRatePpm, takerFeeRatePpm, createdAtEpochMillis,
                updatedAtEpochMillis, clusterPosition, nextStatus, nextRevision);
    }

    public OrderRuntime withStatus(CoreOrderStatus nextStatus, long nextRevision) {
        return withExecution(executedQuantitySteps, remainingQuantitySteps, nextStatus, nextRevision);
    }

    public OrderRuntime withCommitMetadata(long timestamp, long position) {
        return new OrderRuntime(orderId, productLine, userId, symbolId, instrumentVersion, side, priceTicks,
                matchingPriceTicks, quantitySteps, executedQuantitySteps, remainingQuantitySteps, reduceOnly,
                marginMode, positionSide, orderType, timeInForce, postOnly, clientOrderId, commandId,
                makerFeeRatePpm, takerFeeRatePpm, timestamp, timestamp, position, status, revision);
    }
}
