package com.surprising.trading.api.model;

import java.time.Instant;

public record AdminMatchTradeResponse(
        long tradeId,
        long commandId,
        String symbol,
        long takerOrderId,
        long takerUserId,
        OrderSide takerSide,
        MarginMode takerMarginMode,
        PositionSide takerPositionSide,
        long makerOrderId,
        long makerUserId,
        MarginMode makerMarginMode,
        PositionSide makerPositionSide,
        long priceTicks,
        long quantitySteps,
        boolean takerOrderCompleted,
        boolean makerOrderCompleted,
        String traceId,
        Instant eventTime,
        Instant createdAt) {

    public AdminMatchTradeResponse(long tradeId,
                                   long commandId,
                                   String symbol,
                                   long takerOrderId,
                                   long takerUserId,
                                   OrderSide takerSide,
                                   MarginMode takerMarginMode,
                                   long makerOrderId,
                                   long makerUserId,
                                   MarginMode makerMarginMode,
                                   long priceTicks,
                                   long quantitySteps,
                                   boolean takerOrderCompleted,
                                   boolean makerOrderCompleted,
                                   String traceId,
                                   Instant eventTime,
                                   Instant createdAt) {
        this(tradeId, commandId, symbol, takerOrderId, takerUserId, takerSide, takerMarginMode, PositionSide.NET,
                makerOrderId, makerUserId, makerMarginMode, PositionSide.NET, priceTicks, quantitySteps,
                takerOrderCompleted, makerOrderCompleted, traceId, eventTime, createdAt);
    }
}
