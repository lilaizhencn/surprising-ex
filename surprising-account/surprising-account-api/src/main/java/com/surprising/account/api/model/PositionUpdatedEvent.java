package com.surprising.account.api.model;

import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.time.Instant;

public record PositionUpdatedEvent(
        long eventId,
        long tradeId,
        long userId,
        String symbol,
        long instrumentVersion,
        MarginMode marginMode,
        PositionSide positionSide,
        long signedQuantitySteps,
        long entryPriceTicks,
        long realizedPnlUnits,
        Instant eventTime,
        String traceId) {

    public PositionUpdatedEvent {
        marginMode = MarginMode.defaultIfNull(marginMode);
        positionSide = PositionSide.defaultIfNull(positionSide);
    }

    public PositionUpdatedEvent(long eventId,
                                long tradeId,
                                long userId,
                                String symbol,
                                long instrumentVersion,
                                MarginMode marginMode,
                                long signedQuantitySteps,
                                long entryPriceTicks,
                                long realizedPnlUnits,
                                Instant eventTime,
                                String traceId) {
        this(eventId, tradeId, userId, symbol, instrumentVersion, marginMode, PositionSide.NET, signedQuantitySteps,
                entryPriceTicks, realizedPnlUnits, eventTime, traceId);
    }

    public PositionUpdatedEvent(long eventId,
                                long tradeId,
                                long userId,
                                String symbol,
                                long instrumentVersion,
                                long signedQuantitySteps,
                                long entryPriceTicks,
                                long realizedPnlUnits,
                                Instant eventTime) {
        this(eventId, tradeId, userId, symbol, instrumentVersion, signedQuantitySteps, entryPriceTicks,
                realizedPnlUnits, eventTime, null);
    }

    public PositionUpdatedEvent(long eventId,
                                long tradeId,
                                long userId,
                                String symbol,
                                long instrumentVersion,
                                long signedQuantitySteps,
                                long entryPriceTicks,
                                long realizedPnlUnits,
                                Instant eventTime,
                                String traceId) {
        this(eventId, tradeId, userId, symbol, instrumentVersion, MarginMode.CROSS, PositionSide.NET, signedQuantitySteps,
                entryPriceTicks, realizedPnlUnits, eventTime, traceId);
    }
}
