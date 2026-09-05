package com.surprising.aeron.service.state;

import com.surprising.product.api.ProductLine;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreTimeInForce;
import java.util.UUID;

public record CoreOrderState(
        long orderId,
        ProductLine productLine,
        long userId,
        String symbol,
        long instrumentVersion,
        CoreOrderSide side,
        long priceTicks,
        long matchingPriceTicks,
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
        long cumulativeFeeUnits,
        long createdAtEpochMillis,
        long updatedAtEpochMillis,
        long clusterPosition,
        CoreOrderStatus status,
        long revision) {

    public CoreOrderState {
        if (orderId <= 0 || productLine == null || userId <= 0 || instrumentVersion <= 0
                || side == null || priceTicks < 0 || matchingPriceTicks < 0
                || quantitySteps <= 0 || executedQuantitySteps < 0 || remainingQuantitySteps < 0
                || Math.addExact(executedQuantitySteps, remainingQuantitySteps) != quantitySteps
                || marginMode == null || positionSide == null || orderType == null || timeInForce == null
                || clientOrderId == null || clientOrderId.length() > 64 || commandId == null
                || createdAtEpochMillis < 0 || updatedAtEpochMillis < createdAtEpochMillis || clusterPosition < 0
                || postOnly && (orderType != CoreOrderType.LIMIT || timeInForce != CoreTimeInForce.GTX)
                || status == null || revision <= 0) {
            throw new IllegalArgumentException("invalid order state");
        }
        symbol = OrderReservation.normalizeSymbol(symbol);
        if (status == CoreOrderStatus.OPEN && remainingQuantitySteps == 0) {
            throw new IllegalArgumentException("open order must have remaining quantity");
        }
    }

    public CoreOrderState(long orderId, ProductLine productLine, long userId, String symbol,
                          long instrumentVersion, CoreOrderSide side, long priceTicks, long matchingPriceTicks,
                          long quantitySteps, long executedQuantitySteps, long remainingQuantitySteps,
                          boolean reduceOnly, CoreMarginMode marginMode, CorePositionSide positionSide,
                          CoreOrderType orderType, CoreTimeInForce timeInForce, boolean postOnly,
                          String clientOrderId, UUID commandId, long makerFeeRatePpm, long takerFeeRatePpm,
                          long createdAtEpochMillis, long updatedAtEpochMillis, long clusterPosition,
                          CoreOrderStatus status, long revision) {
        this(orderId, productLine, userId, symbol, instrumentVersion, side, priceTicks, matchingPriceTicks,
                quantitySteps, executedQuantitySteps, remainingQuantitySteps, reduceOnly, marginMode, positionSide,
                orderType, timeInForce, postOnly, clientOrderId, commandId, makerFeeRatePpm, takerFeeRatePpm,
                0, createdAtEpochMillis, updatedAtEpochMillis, clusterPosition, status, revision);
    }

    public CoreOrderState(long orderId, ProductLine productLine, long userId, String symbol,
                          long instrumentVersion, CoreOrderSide side, long priceTicks, long quantitySteps,
                          long executedQuantitySteps, long remainingQuantitySteps, boolean reduceOnly,
                          CoreOrderStatus status, long revision) {
        this(orderId, productLine, userId, symbol, instrumentVersion, side, priceTicks, quantitySteps,
                executedQuantitySteps, remainingQuantitySteps, reduceOnly, CoreMarginMode.CROSS,
                CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false,
                "", new UUID(0, orderId), 0, 0, 0, 0, 0, status, revision);
    }

    public CoreOrderState(long orderId, ProductLine productLine, long userId, String symbol,
                          long instrumentVersion, CoreOrderSide side, long priceTicks, long quantitySteps,
                          long executedQuantitySteps, long remainingQuantitySteps, boolean reduceOnly,
                          CoreMarginMode marginMode, CorePositionSide positionSide, CoreOrderType orderType,
                          CoreTimeInForce timeInForce, boolean postOnly, String clientOrderId, UUID commandId,
                          long makerFeeRatePpm, long takerFeeRatePpm, long createdAtEpochMillis,
                          long updatedAtEpochMillis, long clusterPosition, CoreOrderStatus status, long revision) {
        this(orderId, productLine, userId, symbol, instrumentVersion, side, priceTicks, priceTicks, quantitySteps,
                executedQuantitySteps, remainingQuantitySteps, reduceOnly, marginMode, positionSide,
                orderType, timeInForce, postOnly, clientOrderId, commandId, makerFeeRatePpm, takerFeeRatePpm,
                createdAtEpochMillis, updatedAtEpochMillis, clusterPosition, status, revision);
    }

    public CoreOrderState(long orderId, ProductLine productLine, long userId, String symbol,
                          long instrumentVersion, CoreOrderSide side, long priceTicks, long quantitySteps,
                          long executedQuantitySteps, long remainingQuantitySteps, boolean reduceOnly,
                          CoreMarginMode marginMode, CorePositionSide positionSide, CoreOrderType orderType,
                          CoreTimeInForce timeInForce, boolean postOnly, String clientOrderId, UUID commandId,
                          long makerFeeRatePpm, long takerFeeRatePpm, CoreOrderStatus status, long revision) {
        this(orderId, productLine, userId, symbol, instrumentVersion, side, priceTicks, priceTicks, quantitySteps,
                executedQuantitySteps, remainingQuantitySteps, reduceOnly, marginMode, positionSide,
                orderType, timeInForce, postOnly, clientOrderId, commandId, makerFeeRatePpm, takerFeeRatePpm,
                0, 0, 0, status, revision);
    }

    public CoreOrderState(long orderId, ProductLine productLine, long userId, String symbol,
                          long instrumentVersion, CoreOrderSide side, long priceTicks, long matchingPriceTicks,
                          long quantitySteps, long executedQuantitySteps, long remainingQuantitySteps,
                          boolean reduceOnly, CoreMarginMode marginMode, CorePositionSide positionSide,
                          CoreOrderType orderType, CoreTimeInForce timeInForce, boolean postOnly,
                          String clientOrderId, UUID commandId, long makerFeeRatePpm, long takerFeeRatePpm,
                          CoreOrderStatus status, long revision) {
        this(orderId, productLine, userId, symbol, instrumentVersion, side, priceTicks, matchingPriceTicks,
                quantitySteps, executedQuantitySteps, remainingQuantitySteps, reduceOnly, marginMode, positionSide,
                orderType, timeInForce, postOnly, clientOrderId, commandId, makerFeeRatePpm, takerFeeRatePpm,
                0, 0, 0, status, revision);
    }

    public CoreOrderState(long orderId, ProductLine productLine, long userId, String symbol,
                          long instrumentVersion, CoreOrderSide side, long priceTicks, long quantitySteps,
                          long executedQuantitySteps, long remainingQuantitySteps, boolean reduceOnly,
                          CoreMarginMode marginMode, CorePositionSide positionSide,
                          CoreOrderStatus status, long revision) {
        this(orderId, productLine, userId, symbol, instrumentVersion, side, priceTicks, priceTicks, quantitySteps,
                executedQuantitySteps, remainingQuantitySteps, reduceOnly, marginMode, positionSide,
                CoreOrderType.LIMIT, CoreTimeInForce.GTC, false,
                "", new UUID(0, orderId), 0, 0, 0, 0, 0, status, revision);
    }

    public CoreOrderState cancel() {
        if (status.terminal()) {
            return this;
        }
        return new CoreOrderState(orderId, productLine, userId, symbol, instrumentVersion,
                side, priceTicks, matchingPriceTicks, quantitySteps,
                executedQuantitySteps, remainingQuantitySteps, reduceOnly, marginMode, positionSide,
                orderType, timeInForce, postOnly, clientOrderId, commandId, makerFeeRatePpm, takerFeeRatePpm,
                cumulativeFeeUnits, createdAtEpochMillis, updatedAtEpochMillis, clusterPosition,
                CoreOrderStatus.CANCELED,
                Math.incrementExact(revision));
    }

    public CoreOrderState reject() {
        if (status.terminal()) {
            return this;
        }
        return new CoreOrderState(orderId, productLine, userId, symbol, instrumentVersion,
                side, priceTicks, matchingPriceTicks, quantitySteps,
                executedQuantitySteps, remainingQuantitySteps, reduceOnly, marginMode, positionSide,
                orderType, timeInForce, postOnly, clientOrderId, commandId, makerFeeRatePpm, takerFeeRatePpm,
                cumulativeFeeUnits, createdAtEpochMillis, updatedAtEpochMillis, clusterPosition,
                CoreOrderStatus.REJECTED,
                Math.incrementExact(revision));
    }

    public CoreOrderState fill(long quantitySteps) {
        return fill(quantitySteps, 0);
    }

    public CoreOrderState fill(long quantitySteps, long feeUnits) {
        if (status != CoreOrderStatus.OPEN || quantitySteps <= 0 || quantitySteps > remainingQuantitySteps) {
            throw new IllegalStateException("invalid order fill");
        }
        long nextExecuted = Math.addExact(executedQuantitySteps, quantitySteps);
        long nextRemaining = Math.subtractExact(remainingQuantitySteps, quantitySteps);
        return new CoreOrderState(orderId, productLine, userId, symbol, instrumentVersion,
                side, priceTicks, matchingPriceTicks, quantitySteps(), nextExecuted, nextRemaining,
                reduceOnly, marginMode, positionSide,
                orderType, timeInForce, postOnly,
                clientOrderId, commandId, makerFeeRatePpm, takerFeeRatePpm,
                Math.addExact(cumulativeFeeUnits, feeUnits), createdAtEpochMillis, updatedAtEpochMillis,
                clusterPosition,
                nextRemaining == 0 ? CoreOrderStatus.FILLED : CoreOrderStatus.OPEN,
                Math.incrementExact(revision));
    }

    public CoreOrderState replacePrice(long newPriceTicks) {
        if (status != CoreOrderStatus.OPEN || newPriceTicks <= 0) {
            throw new IllegalStateException("invalid order replace");
        }
        return new CoreOrderState(orderId, productLine, userId, symbol, instrumentVersion,
                side, newPriceTicks, newPriceTicks, quantitySteps, executedQuantitySteps, remainingQuantitySteps,
                reduceOnly, marginMode, positionSide, orderType, timeInForce, postOnly,
                clientOrderId, commandId, makerFeeRatePpm, takerFeeRatePpm,
                cumulativeFeeUnits, createdAtEpochMillis, updatedAtEpochMillis, clusterPosition,
                status, Math.incrementExact(revision));
    }

    public CoreOrderState withCommitMetadata(long timestamp, long position) {
        return new CoreOrderState(orderId, productLine, userId, symbol, instrumentVersion, side, priceTicks,
                matchingPriceTicks,
                quantitySteps, executedQuantitySteps, remainingQuantitySteps, reduceOnly, marginMode, positionSide,
                orderType, timeInForce, postOnly, clientOrderId, commandId, makerFeeRatePpm, takerFeeRatePpm,
                cumulativeFeeUnits, createdAtEpochMillis == 0 ? timestamp : createdAtEpochMillis,
                timestamp, position, status, revision);
    }
}
