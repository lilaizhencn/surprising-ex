package com.surprising.trading.api.model;

import java.time.Instant;

public record MatchTradeEvent(
        long tradeId,
        long commandId,
        String symbol,
        long takerOrderId,
        long takerInstrumentVersion,
        long takerUserId,
        OrderSide takerSide,
        long makerOrderId,
        long makerInstrumentVersion,
        long makerUserId,
        long priceTicks,
        long quantitySteps,
        boolean takerOrderCompleted,
        boolean makerOrderCompleted,
        Instant eventTime,
        String traceId) {

    public MatchTradeEvent(long tradeId,
                           long commandId,
                           String symbol,
                           long takerOrderId,
                           long takerInstrumentVersion,
                           long takerUserId,
                           OrderSide takerSide,
                           long makerOrderId,
                           long makerInstrumentVersion,
                           long makerUserId,
                           long priceTicks,
                           long quantitySteps,
                           boolean takerOrderCompleted,
                           boolean makerOrderCompleted,
                           Instant eventTime) {
        this(tradeId, commandId, symbol, takerOrderId, takerInstrumentVersion, takerUserId, takerSide, makerOrderId,
                makerInstrumentVersion, makerUserId, priceTicks, quantitySteps, takerOrderCompleted,
                makerOrderCompleted, eventTime, null);
    }
}
