package com.surprising.account.api.model;

import com.surprising.trading.api.model.MarginMode;
import java.time.Instant;

public record PositionUpdatedEvent(
        long eventId,
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

    public PositionUpdatedEvent {
        marginMode = MarginMode.defaultIfNull(marginMode);
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
        this(eventId, tradeId, userId, symbol, instrumentVersion, MarginMode.CROSS, signedQuantitySteps,
                entryPriceTicks, realizedPnlUnits, eventTime, traceId);
    }
}
