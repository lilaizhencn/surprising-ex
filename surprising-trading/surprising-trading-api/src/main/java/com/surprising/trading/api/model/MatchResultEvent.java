package com.surprising.trading.api.model;

import java.time.Instant;
import java.util.List;

public record MatchResultEvent(
        long commandId,
        long orderId,
        long userId,
        String symbol,
        long instrumentVersion,
        OrderCommandType commandType,
        String resultCode,
        long filledQuantitySteps,
        OrderStatus orderStatus,
        Instant eventTime,
        List<MatchTradeEvent> trades,
        String traceId) {

    public MatchResultEvent(long commandId,
                            long orderId,
                            long userId,
                            String symbol,
                            long instrumentVersion,
                            OrderCommandType commandType,
                            String resultCode,
                            long filledQuantitySteps,
                            OrderStatus orderStatus,
                            Instant eventTime,
                            List<MatchTradeEvent> trades) {
        this(commandId, orderId, userId, symbol, instrumentVersion, commandType, resultCode, filledQuantitySteps,
                orderStatus, eventTime, trades, null);
    }
}
