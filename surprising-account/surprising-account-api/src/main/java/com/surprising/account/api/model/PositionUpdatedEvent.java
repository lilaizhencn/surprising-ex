package com.surprising.account.api.model;

import java.time.Instant;

public record PositionUpdatedEvent(
        long eventId,
        long tradeId,
        long userId,
        String symbol,
        long instrumentVersion,
        long signedQuantitySteps,
        long entryPriceTicks,
        long realizedPnlUnits,
        Instant eventTime,
        String traceId) {

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
}
