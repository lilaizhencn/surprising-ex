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
        MarginMode takerMarginMode,
        PositionSide takerPositionSide,
        long makerOrderId,
        long makerInstrumentVersion,
        long makerUserId,
        MarginMode makerMarginMode,
        PositionSide makerPositionSide,
        long priceTicks,
        long quantitySteps,
        boolean takerOrderCompleted,
        boolean makerOrderCompleted,
        Instant eventTime,
        String traceId) {

    public MatchTradeEvent {
        takerMarginMode = MarginMode.defaultIfNull(takerMarginMode);
        makerMarginMode = MarginMode.defaultIfNull(makerMarginMode);
        takerPositionSide = PositionSide.defaultIfNull(takerPositionSide);
        makerPositionSide = PositionSide.defaultIfNull(makerPositionSide);
    }

    public MatchTradeEvent(long tradeId,
                           long commandId,
                           String symbol,
                           long takerOrderId,
                           long takerInstrumentVersion,
                           long takerUserId,
                           OrderSide takerSide,
                           MarginMode takerMarginMode,
                           long makerOrderId,
                           long makerInstrumentVersion,
                           long makerUserId,
                           MarginMode makerMarginMode,
                           long priceTicks,
                           long quantitySteps,
                           boolean takerOrderCompleted,
                           boolean makerOrderCompleted,
                           Instant eventTime,
                           String traceId) {
        this(tradeId, commandId, symbol, takerOrderId, takerInstrumentVersion, takerUserId, takerSide,
                takerMarginMode, PositionSide.NET, makerOrderId, makerInstrumentVersion, makerUserId,
                makerMarginMode, PositionSide.NET, priceTicks, quantitySteps, takerOrderCompleted,
                makerOrderCompleted, eventTime, traceId);
    }

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
                           Instant eventTime,
                           String traceId) {
        this(tradeId, commandId, symbol, takerOrderId, takerInstrumentVersion, takerUserId, takerSide,
                MarginMode.CROSS, PositionSide.NET, makerOrderId, makerInstrumentVersion, makerUserId,
                MarginMode.CROSS, PositionSide.NET, priceTicks, quantitySteps, takerOrderCompleted,
                makerOrderCompleted, eventTime, traceId);
    }
}
