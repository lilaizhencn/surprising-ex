package com.surprising.trading.api.model;

import java.time.Instant;

public record OrderCommandEvent(
        OrderCommandType commandType,
        long commandId,
        long orderId,
        long userId,
        String clientOrderId,
        String symbol,
        long instrumentVersion,
        OrderSide side,
        OrderType orderType,
        TimeInForce timeInForce,
        long priceTicks,
        long quantitySteps,
        MarginMode marginMode,
        PositionSide positionSide,
        Long makerFeeRatePpm,
        Long takerFeeRatePpm,
        boolean reduceOnly,
        boolean postOnly,
        Instant commandTime,
        String traceId) {

    public OrderCommandEvent {
        marginMode = MarginMode.defaultIfNull(marginMode);
        positionSide = PositionSide.defaultIfNull(positionSide);
        makerFeeRatePpm = requireFeeRate(makerFeeRatePpm, "makerFeeRatePpm");
        takerFeeRatePpm = requireFeeRate(takerFeeRatePpm, "takerFeeRatePpm");
    }

    public OrderCommandEvent(OrderCommandType commandType,
                             long commandId,
                             long orderId,
                             long userId,
                             String clientOrderId,
                             String symbol,
                             long instrumentVersion,
                             OrderSide side,
                             OrderType orderType,
                             TimeInForce timeInForce,
                             long priceTicks,
                             long quantitySteps,
                             MarginMode marginMode,
                             long makerFeeRatePpm,
                             long takerFeeRatePpm,
                             boolean reduceOnly,
                             boolean postOnly,
                             Instant commandTime,
                             String traceId) {
        this(commandType, commandId, orderId, userId, clientOrderId, symbol, instrumentVersion, side, orderType,
                timeInForce, priceTicks, quantitySteps, marginMode, PositionSide.NET, makerFeeRatePpm,
                takerFeeRatePpm, reduceOnly, postOnly, commandTime, traceId);
    }

    public OrderCommandEvent(OrderCommandType commandType,
                             long commandId,
                             long orderId,
                             long userId,
                             String clientOrderId,
                             String symbol,
                             long instrumentVersion,
                             OrderSide side,
                             OrderType orderType,
                             TimeInForce timeInForce,
                             long priceTicks,
                             long quantitySteps,
                             long makerFeeRatePpm,
                             long takerFeeRatePpm,
                             boolean reduceOnly,
                             boolean postOnly,
                             Instant commandTime) {
        this(commandType, commandId, orderId, userId, clientOrderId, symbol, instrumentVersion, side, orderType,
                timeInForce, priceTicks, quantitySteps, MarginMode.CROSS, PositionSide.NET, makerFeeRatePpm,
                takerFeeRatePpm, reduceOnly, postOnly, commandTime, null);
    }

    public OrderCommandEvent(OrderCommandType commandType,
                             long commandId,
                             long orderId,
                             long userId,
                             String clientOrderId,
                             String symbol,
                             long instrumentVersion,
                             OrderSide side,
                             OrderType orderType,
                             TimeInForce timeInForce,
                             long priceTicks,
                             long quantitySteps,
                             long makerFeeRatePpm,
                             long takerFeeRatePpm,
                             boolean reduceOnly,
                             boolean postOnly,
                             Instant commandTime,
                             String traceId) {
        this(commandType, commandId, orderId, userId, clientOrderId, symbol, instrumentVersion, side, orderType,
                timeInForce, priceTicks, quantitySteps, MarginMode.CROSS, PositionSide.NET, makerFeeRatePpm,
                takerFeeRatePpm, reduceOnly, postOnly, commandTime, traceId);
    }

    private static long requireFeeRate(Long feeRatePpm, String field) {
        if (feeRatePpm == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        if (feeRatePpm < -1_000_000L || feeRatePpm > 1_000_000L) {
            throw new IllegalArgumentException(field + " must be in [-1000000, 1000000]");
        }
        return feeRatePpm;
    }
}
